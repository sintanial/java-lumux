package com.lumux;

import com.lumux.buffer.AutoGrowByteBuffer;
import com.lumux.buffer.BufferUtil;
import com.lumux.exception.ErrorCodeException;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.util.concurrent.TimeoutException;

public class Stream {
    private static final int DEFAULT_PRIORITY = 100;

    int id;

    Session session;

    int maxBucketSize;

    CompletionHandler<Integer, Stream> readCh;
    boolean readComplete = true;
    long readDeadline;
    ByteBuffer readDst;
    AutoGrowByteBuffer readBuf;
    int readTokenBucket;

    CompletionHandler<Integer, Stream> writeCh;
    boolean writeComplete = true;
    long writeDeadline;
    ByteBuffer writeSrc;
    int writePriority;
    int writeTokenBucket;

    boolean isClosed;
    IOException closedException;

    Stream(int id, Session session, int maxBucketSize) {
        this.id = id;
        this.session = session;
        this.maxBucketSize = maxBucketSize;
        this.readTokenBucket = maxBucketSize;
        this.writeTokenBucket = maxBucketSize;
    }

    public int getId() {
        return id;
    }

    public synchronized void close(final int code, final CompletionHandler<Void, Stream> handler) {
        try {
            session.loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (isClosed) {
                        if (handler != null) handler.failed(closedException, Stream.this);
                        return;
                    }

                    closeStream(code);
                    if (handler != null) handler.completed(null, Stream.this);
                }
            });
        } catch (ClosedChannelException e) {
            if (handler != null) handler.failed(e, this);
        }
    }

    public synchronized void close(final int code) {
        close(code, null);
    }

    public synchronized void close() {
        close(0);
    }

    public synchronized void read(ByteBuffer dst, CompletionHandler<Integer, Stream> handler) {
        read(dst, 0, handler);
    }

    public synchronized void read(final ByteBuffer dst, final int timeout, final CompletionHandler<Integer, Stream> handler) {
        try {
            session.loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (dst.remaining() == 0) {
                        handler.failed(new BufferUnderflowException(), Stream.this);
                    } else if (readBuf != null && readBuf.remaining() > 0) {
                        handler.completed(readBuf.putTo(dst), Stream.this);
                    } else if (!readComplete) {
                        handler.failed(new ReadPendingException(), Stream.this);
                    } else if (isClosed) {
                        handler.failed(closedException, Stream.this);
                    } else {
                        readComplete = false;
                        readCh = handler;
                        readDeadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
                        readDst = dst;
                    }
                }
            });
        } catch (ClosedChannelException e) {
            handler.failed(e, this);
        }
    }

    void notifyReadData(ByteBuffer data) {
        readTokenBucket -= data.remaining();
        if (readTokenBucket <= 0) {
            session.writeWinFrame(id, maxBucketSize);
            readTokenBucket += maxBucketSize;
        }

        if (!readComplete) {
            BufferUtil.putAtLeast(readDst, data);
        }

        if (data.remaining() > 0) {
            if (readBuf == null) readBuf = new AutoGrowByteBuffer(data.remaining());
            readBuf.put(data);
        }

        if (!readComplete) {
            readComplete = true;
            readCh.completed(readDst.limit(), this);
        }
    }

    public synchronized void write(final ByteBuffer src, CompletionHandler<Integer, Stream> handler) {
        write(src, 0, DEFAULT_PRIORITY, handler);
    }

    public synchronized void write(final ByteBuffer src, int timeout, CompletionHandler<Integer, Stream> handler) {
        write(src, timeout, DEFAULT_PRIORITY, handler);
    }

    public synchronized void write(final ByteBuffer src, final int timeout, final int priority, final CompletionHandler<Integer, Stream> handler) {
        try {
            session.loop.spawn(new Runnable() {
                @Override
                public void run() {
                    if (!writeComplete) {
                        handler.failed(new WritePendingException(), Stream.this);
                        return;
                    } else if (isClosed) {
                        handler.failed(closedException, Stream.this);
                        return;
                    }

                    writeComplete = false;
                    writeCh = handler;
                    writeDeadline = timeout > 0 ? System.currentTimeMillis() + timeout : 0;
                    writePriority = priority;
                    writeSrc = src;

                    tryWriteData();
                }
            });
        } catch (ClosedChannelException e) {
            handler.failed(e, this);
        }
    }

    void tryWriteData() {
        if (writeComplete || writeTokenBucket < 0) return;

        writeTokenBucket -= writeSrc.remaining();
        session.addPshFrameToWriteQueue(id, writePriority, writeSrc);
    }

    void updateWin(int win) {
        writeTokenBucket += win;
        tryWriteData();
    }

    void notifyWrite() {
        if (writeComplete) return;

        writeComplete = true;
        writeCh.completed(writeSrc.limit(), this);
    }

    private void closeStream(int code) {
        this.isClosed = true;
        this.closedException = new ErrorCodeException(code);
        session.finStream(id, code);
    }

    void closeBySession(int code) {
        this.isClosed = true;
        this.closedException = new ErrorCodeException(code);
        notifyClose();
    }

    void closeBySession(IOException e) {
        this.isClosed = true;
        this.closedException = e;
        notifyClose();
    }

    private void notifyClose() {
        if (!readComplete) readCh.failed(closedException, this);
        if (!writeComplete) writeCh.failed(closedException, this);
    }

    void checkReadWriteTimeouts(long now) {
        if (isReadTimeoutReached(now)) {
            readComplete = true;
            readCh.failed(new TimeoutException(), this);
        }

        if (isWriteTimeoutReached(now)) {
            session.rmPshFrameFromQueues(id);
            writeComplete = true;
            writeCh.failed(new TimeoutException(), this);
        }
    }

    boolean isReadTimeoutReached(long now) {
        if (readComplete || readDeadline == 0) return false;
        return now > readDeadline;
    }

    boolean isWriteTimeoutReached(long now) {
        if (writeComplete || writeDeadline == 0) return false;
        return now > writeDeadline;
    }
}
