package com.lumux;

import com.lumux.buffer.BufferUtil;
import com.lumux.eventloop.EventLoop;
import com.lumux.exception.InvalidFrameException;
import com.lumux.exception.KeepAliveTimeoutException;
import com.lumux.exception.TooManyStreamsException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AcceptPendingException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSession;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.FINISHED;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;

public class Session {
    public static Session server(Config config, EventLoop eventLoop, SocketChannel socket) throws IOException {
        return new Session(config, eventLoop, socket, false, null);
    }

    public static Session server(EventLoop eventLoop, SocketChannel socket) throws IOException {
        return new Session(new Config(), eventLoop, socket, false, null);
    }

    public static Session server(Config config, EventLoop eventLoop, SocketChannel socket, SSLEngine sslEngine) throws IOException {
        return new Session(config, eventLoop, socket, false, sslEngine);
    }

    public static Session server(EventLoop eventLoop, SocketChannel socket, SSLEngine sslEngine) throws IOException {
        return new Session(new Config(), eventLoop, socket, false, sslEngine);
    }

    public static Session client(Config config, EventLoop eventLoop, SocketChannel socket) throws IOException {
        return new Session(config, eventLoop, socket, true, null);
    }

    public static Session client(EventLoop eventLoop, SocketChannel socket) throws IOException {
        return new Session(new Config(), eventLoop, socket, true, null);
    }

    public static Session client(Config config, EventLoop eventLoop, SocketChannel socket, SSLEngine sslEngine) throws IOException {
        return new Session(config, eventLoop, socket, true, sslEngine);
    }

    public static Session client(EventLoop eventLoop, SocketChannel socket, SSLEngine sslEngine) throws IOException {
        return new Session(new Config(), eventLoop, socket, true, sslEngine);
    }

    final Config config;
    final EventLoop loop;
    private final SocketChannel socket;
    private final SelectionKey selectionKey;

    private final SSLEngine sslEngine;
    private ByteBuffer wAppDataBuffer;
    private ByteBuffer wNetDataBuffer;
    private ByteBuffer rAppDataBuffer;
    private ByteBuffer rNetDataBuffer;

    private int nextStreamID;
    private HashMap<Integer, Stream> streams;


    private LinkedList<Stream> acceptBacklog;
    private CompletionHandler<Stream, Void> acceptCh;
    private boolean acceptComplete = true;
    private long acceptTimeout;

    private PriorityQueue<PriorityFrame> writeQueue;

    private int writeSequenceNum = 0;

    private FrameReader fr;
    private FrameHandler fh;

    private boolean isClosed;
    private IOException closedException;

    private TickHandler tickHandler;

    private long lastReadTimestamp;
    private boolean isPingSend = false;

    private Session(Config config, EventLoop loop, SocketChannel socket, boolean client, SSLEngine sslEngine) throws IOException {
        this.config = config;
        this.loop = loop;
        this.socket = socket;
        this.sslEngine = sslEngine;

        if (sslEngine != null) {
            if (sslEngine.getHandshakeStatus() != FINISHED && sslEngine.getHandshakeStatus() != NOT_HANDSHAKING) {
                throw new IOException("ssl handshake is not finished yet");
            }

            // todo: передавать буфер из хендшейка
            SSLSession session = sslEngine.getSession();

            rAppDataBuffer = ByteBuffer.allocate(session.getApplicationBufferSize());
            wAppDataBuffer = ByteBuffer.allocate(config.maxFrameSize + 2 * Frame.HEADER_SIZE);

            rNetDataBuffer = ByteBuffer.allocate(session.getPacketBufferSize());
            wNetDataBuffer = ByteBuffer.allocate(session.getPacketBufferSize());
        } else {
            rNetDataBuffer = ByteBuffer.allocate(config.maxFrameSize + 2 * Frame.HEADER_SIZE);
            wAppDataBuffer = ByteBuffer.allocate(config.maxFrameSize + 2 * Frame.HEADER_SIZE);
        }

        this.selectionKey = loop.register(socket, SelectionKey.OP_READ, new KeyHandler());

        this.nextStreamID = client ? 1 : 0;
        this.streams = new HashMap<>();

        this.acceptBacklog = new LinkedList<>();

        this.writeQueue = new PriorityQueue<>();
        this.fr = new FrameReader(config.maxFrameSize);
        this.fh = new FrameHandler();

        this.lastReadTimestamp = System.currentTimeMillis();

        this.tickHandler = new TickHandler();
        this.loop.addTickHandler(tickHandler);
    }

    // close socket connection
    public synchronized void close(final IOException e, final CompletionHandler<Void, Void> handler) {
        try {
            loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (isClosed || !selectionKey.isValid()) {
                        if (handler != null) handler.failed(closedException, null);
                        return;
                    }

                    closeSession(e);
                    if (handler != null) handler.completed(null, null);
                }
            });
        } catch (ClosedChannelException e1) {
            if (handler != null) handler.failed(e1, null);
        }
    }

    public synchronized void close(CompletionHandler<Void, Void> handler) {
        close(new EOFException(), handler);
    }

    public synchronized void close() {
        close(null);
    }

    public synchronized void openStream(final CompletionHandler<Stream, Void> handler) {
        try {
            loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (isClosed || !selectionKey.isValid()) {
                        handler.failed(closedException, null);
                        return;
                    }

                    nextStreamID += 2;
                    final Stream stream = new Stream(nextStreamID, Session.this, config.maxStreamWindowSize);
                    writeFrame(new PriorityFrame(Frame.CMD_SYN, stream.id, 0));
                    streams.put(stream.id, stream);

                    handler.completed(stream, null);
                }
            });
        } catch (ClosedChannelException e) {
            handler.failed(e, null);
        }
    }

    public synchronized void acceptStream(CompletionHandler<Stream, Void> handler) {
        acceptStream(0, handler);
    }

    public synchronized void acceptStream(final int timeout, final CompletionHandler<Stream, Void> handler) {
        try {
            loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (!acceptComplete) {
                        handler.failed(new AcceptPendingException(), null);
                        return;
                    } else if (isClosed || !selectionKey.isValid()) {
                        handler.failed(closedException, null);
                        return;
                    } else if (acceptBacklog.size() > 0) {
                        handler.completed(acceptBacklog.poll(), null);
                        return;
                    }

                    acceptComplete = false;
                    acceptCh = handler;
                    acceptTimeout = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
                }
            });
        } catch (ClosedChannelException e) {
            handler.failed(e, null);
        }
    }

    private void notifyAcceptStream(Stream stream) throws IOException {
        if (!acceptComplete) {
            acceptComplete = true;
            acceptCh.completed(stream, null);
        } else {
            if (config.streamAcceptBacklogSize > 0 && acceptBacklog.size() > config.streamAcceptBacklogSize) {
                throw new TooManyStreamsException("too many streams " + acceptBacklog.size());
            }

            acceptBacklog.add(stream);
        }
    }

    private void checkAcceptTimeout(long now) {
        if (acceptComplete || acceptTimeout == 0) return;

        if (now > acceptTimeout) {
            acceptComplete = true;
            acceptCh.failed(new TimeoutException(), null);
        }
    }

    private void closeSession(IOException e) {
        if (isClosed) return;

        isClosed = true;
        closedException = e;
        if (acceptCh != null) {
            acceptCh.failed(closedException, null);
            acceptCh = null;
        }

        if (tickHandler != null) {
            try {
                loop.rmTickHandler(tickHandler);
            } catch (ClosedChannelException ignored) {
            }
        }

        closeStreams(e);
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private void processRead() throws IOException {
        // clear buffer before read new frame
        rNetDataBuffer.clear();

        int readed = socket.read(rNetDataBuffer);
        // means socket is closed
        if (readed == -1) throw new EOFException();
        if (readed == 0) return;

        ByteBuffer buf;
        if (sslEngine != null) {
            rNetDataBuffer.flip();
            while (rNetDataBuffer.hasRemaining()) {
                SSLEngineResult result = sslEngine.unwrap(rNetDataBuffer, rAppDataBuffer);
                switch (result.getStatus()) {
                    case OK:
                        break;
                    case BUFFER_OVERFLOW:
                        rAppDataBuffer = BufferUtil.enlargeApplicationBuffer(sslEngine, rAppDataBuffer);
                        break;
                    case BUFFER_UNDERFLOW:
                        rNetDataBuffer.compact();
                        if (sslEngine.getSession().getPacketBufferSize() > rNetDataBuffer.capacity()) {
                            rNetDataBuffer = BufferUtil.handleBufferUnderflow(sslEngine, rNetDataBuffer);
                        }

                        readed = socket.read(rNetDataBuffer);
                        if (readed == -1) throw new EOFException();
                        rNetDataBuffer.flip();
                        break;
                    case CLOSED:
                        throw new EOFException();
                    default:
                        throw new IllegalStateException();
                }
            }

            buf = rAppDataBuffer;
        } else {
            buf = rNetDataBuffer;
        }

        lastReadTimestamp = System.currentTimeMillis();

        buf.flip();
        while (buf.hasRemaining()) {
            // todo: записывать не больше чем буффер в реадере
            fr.put(buf);

            while (fr.read(this.fh)) {
            }
        }
        buf.clear();
    }

    private void tryWriteFrameToBuf() {
        while (wAppDataBuffer.remaining() >= Frame.HEADER_SIZE) {
            PriorityFrame frame = writeQueue.poll();
            if (frame == null) break;

            int minRemaining = Frame.HEADER_SIZE;
            if (frame.cmd == Frame.CMD_PSH) {
                minRemaining += 1024;
            } else if (frame.data != null) {
                minRemaining += frame.data.remaining();
            } else if (frame.smallData != null) {
                minRemaining += frame.smallData.length;
            }

            if (wAppDataBuffer.remaining() < minRemaining) {
                writeQueue.add(frame);
                break;
            }

            int len = 0;
            if (frame.data != null) {
                len = frame.data.remaining() > wAppDataBuffer.remaining() ? wAppDataBuffer.remaining() - Frame.HEADER_SIZE : frame.data.remaining();
            } else if (frame.smallData != null) {
                len = frame.smallData.length;
            }

            wAppDataBuffer.put((byte) frame.cmd);
            wAppDataBuffer.putInt(len);
            wAppDataBuffer.putInt(frame.sid & 0xff);

            if (frame.smallData != null) {
                wAppDataBuffer.put(frame.smallData);
            } else if (frame.data != null) {
                if (frame.data.remaining() > wAppDataBuffer.remaining()) {
                    BufferUtil.putAtLeast(wAppDataBuffer, frame.data);
                    writeQueue.add(frame);
                } else {
                    wAppDataBuffer.put(frame.data);
                    if (frame.cmd == Frame.CMD_PSH) streams.get(frame.sid).notifyWrite();
                }
            }
        }
    }

    private void processWrite() throws IOException {
        while (true) {
            tryWriteFrameToBuf();

            ByteBuffer buf;
            if (sslEngine != null) {
                wAppDataBuffer.flip();
                while (wAppDataBuffer.hasRemaining()) {
                    SSLEngineResult result = sslEngine.wrap(wAppDataBuffer, wNetDataBuffer);
                    switch (result.getStatus()) {
                        case OK:
                            break;
                        case BUFFER_OVERFLOW:
                            wNetDataBuffer = BufferUtil.enlargePacketBuffer(sslEngine, wNetDataBuffer);
                            break;
                        case BUFFER_UNDERFLOW:
                            throw new IllegalStateException();
                        case CLOSED:
                            throw new EOFException();
                        default:
                            throw new IllegalStateException();
                    }
                }
                wAppDataBuffer.clear();

                buf = wNetDataBuffer;
            } else {
                buf = wAppDataBuffer;
            }

            buf.flip();

            int needToWrite = buf.remaining();

            // если буффер пустой, ничего не пишим, ждём когда заполнится
            if (needToWrite == 0) {
                buf.clear();
                selectionKey.interestOps(selectionKey.interestOps() & ~SelectionKey.OP_WRITE);
                return;
            }

            int writed = socket.write(buf);
            if (writed > 0) {
                if (writed == needToWrite) {
                    buf.clear();
                    if (writeQueue.size() == 0) {
                        selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                        return;
                    }
                } else {
                    buf.compact();
                    selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                    return;
                }
            } else {
                // todo: проверить
                buf.position(buf.limit());
                buf.limit(buf.capacity());

                selectionKey.interestOps(selectionKey.interestOps() | SelectionKey.OP_WRITE);
                return;
            }
        }
    }

    private void closeStreams(IOException e) {
        for (Map.Entry<Integer, Stream> entry : streams.entrySet()) {
            closeStream(entry.getValue(), e);
        }
    }

    void closeStream(Stream stream, int code) {
        stream.closeBySession(code);
        streams.remove(stream.id);
    }

    void closeStream(Stream stream, IOException e) {
        stream.closeBySession(e);
        streams.remove(stream.id);
    }

    void closeStream(int id, int code) {
        Stream stream = streams.get(id);
        if (stream == null) return;

        closeStream(stream, code);
    }


    void finStream(int id, int code) {
        Stream stream = streams.get(id);
        if (stream == null) return;

        streams.remove(id);
        writeFinFrame(id, code);
    }

    private void checkStreamsTimeout(long now) {
        for (Map.Entry<Integer, Stream> entry : streams.entrySet()) {
            Stream stream = entry.getValue();
            stream.checkReadWriteTimeouts(now);
        }
    }

    class FrameHandler implements FrameReader.FrameHandler {
        @Override
        public void handle(short cmd, int len, int sid, ByteBuffer data) throws IOException {
            switch (cmd) {
                case Frame.CMD_SYN: {
                    Stream stream = new Stream(sid, Session.this, config.maxStreamWindowSize);
                    streams.put(sid, stream);
                    notifyAcceptStream(stream);
                    break;
                }
                case Frame.CMD_PSH: {
                    Stream stream = streams.get(sid);
                    if (stream != null) stream.notifyReadData(data);
                    break;
                }
                case Frame.CMD_FIN: {
                    short code = (data.getShort());
                    closeStream(sid, code);
                    break;
                }
                case Frame.CMD_WIN: {
                    Stream stream = streams.get(sid);
                    if (stream != null) stream.updateWin(data.getInt());
                    break;
                }
                case Frame.CMD_NOP: {
                    if (data == null) throw new InvalidFrameException();

                    if (data.get() == 0) {
                        System.out.println("send pong");
                        writeNopFrame(true);
                    } else {
                        System.out.println("receive pong");
                        isPingSend = false;
                    }
                    break;
                }
            }
        }
    }

    void checkKeepAliveTimeout(long now) {
        if (!config.keepAliveEnabled) return;

        if (!isPingSend) {
            if (now > lastReadTimestamp + config.keepAliveInterval) {
                isPingSend = true;
                lastReadTimestamp = now;

                System.out.println("send ping");
                writeNopFrame(false);
            }
        } else {
            if (lastReadTimestamp > 0 && now > lastReadTimestamp + config.keepAliveTimeout) {
                closeSession(new KeepAliveTimeoutException());
            }
        }
    }

    void writeFinFrame(int sid, int code) {
        writeFrame(new PriorityFrame(Frame.CMD_FIN, sid, 0, new byte[]{(byte) (code >> 8), (byte) code}));
    }

    void writeNopFrame(boolean ack) {
        writeFrame(new PriorityFrame(Frame.CMD_NOP, 0, 0, new byte[]{(byte) (ack ? 1 : 0)}));
    }

    void writeWinFrame(int sid, int win) {
        writeFrame(new PriorityFrame(Frame.CMD_WIN, sid, 0, new byte[]{(byte) (win >> 24), (byte) (win >> 16), (byte) (win >> 8), (byte) win}));
    }

    void addPshFrameToWriteQueue(int sid, int priority, ByteBuffer data) {
        writeFrame(new PriorityFrame(Frame.CMD_PSH, sid, priority, data));
    }

    boolean rmPshFrameFromQueues(int sid) {
        Iterator<PriorityFrame> it = writeQueue.iterator();

        while (it.hasNext()) {
            PriorityFrame fr = it.next();
            if (fr.sid == sid && fr.cmd == Frame.CMD_PSH) {
                it.remove();
                return true;
            }
        }

        return false;
    }

    private void addFrameToQueue(PriorityFrame frame) {
        frame.sequence = writeSequenceNum++;
        writeQueue.add(frame);
    }

    private void writeFrame(PriorityFrame frame) {
        addFrameToQueue(frame);
        flush();
    }

    private void flush() {
        try {
            processWrite();
        } catch (IOException e) {
            closeSession(e);
        }
    }

    private class TickHandler implements EventLoop.TickHandler {
        @Override
        public void handle() {
            if (isClosed) return;

            long now = System.currentTimeMillis();
            checkKeepAliveTimeout(now);
            checkAcceptTimeout(now);
            checkStreamsTimeout(now);
        }
    }

    private class KeyHandler implements EventLoop.KeyHandler {
        @Override
        public void handle(SelectionKey key) {
            if (isClosed) return;

            try {
                if (!key.isValid()) {
                    throw new EOFException();
                }

                if (key.isReadable()) {
                    processRead();
                } else if (key.isWritable()) {
                    processWrite();
                }
            } catch (IOException e) {
                closeSession(e);
            }
        }
    }
}
