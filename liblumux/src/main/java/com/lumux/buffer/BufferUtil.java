package com.lumux.buffer;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;

public class BufferUtil {
    public static void putAtLeast(ByteBuffer dst, ByteBuffer src) {
        if (src.remaining() > dst.remaining()) {
            int limit = src.limit();
            src.limit(src.position() + dst.remaining());
            dst.put(src);
            src.limit(limit);
        } else {
            dst.put(src);
        }
    }

    public static ByteBuffer enlargePacketBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getPacketBufferSize());
    }

    public static ByteBuffer enlargeApplicationBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getApplicationBufferSize());
    }

    public static ByteBuffer enlargeBuffer(ByteBuffer buffer, int sessionProposedCapacity) {
        if (sessionProposedCapacity > buffer.capacity()) {
            buffer = ByteBuffer.allocate(sessionProposedCapacity);
        } else {
            buffer = ByteBuffer.allocate(buffer.capacity() * 2);
        }
        return buffer;
    }

    public static ByteBuffer handleBufferOverflow(SSLEngine engine, ByteBuffer buffer) {
        if (engine.getSession().getApplicationBufferSize() < buffer.limit()) {
            return buffer;
        } else {
            ByteBuffer replaceBuffer = enlargeApplicationBuffer(engine, buffer);
            buffer.flip();
            replaceBuffer.put(buffer);
            return replaceBuffer;
        }
    }

    public static ByteBuffer handleBufferUnderflow(SSLEngine engine, ByteBuffer buffer) {
        if (engine.getSession().getPacketBufferSize() < buffer.limit()) {
            return buffer;
        } else {
            ByteBuffer replaceBuffer = enlargePacketBuffer(engine, buffer);
            buffer.flip();
            replaceBuffer.put(buffer);
            return replaceBuffer;
        }
    }

}
