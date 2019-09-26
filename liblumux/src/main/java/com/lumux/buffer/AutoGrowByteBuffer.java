package com.lumux.buffer;

import java.nio.Buffer;
import java.nio.ByteBuffer;

public class AutoGrowByteBuffer {
    private int initCapacity;
    private ByteBuffer buffer;

    public AutoGrowByteBuffer(int capacity) {
        this.initCapacity = capacity;
        this.buffer = ByteBuffer.allocate(capacity);
    }

    public boolean put(ByteBuffer src) {
        if (isNeedToGrow(src)) grow(src);

        buffer.put(src);
        return true;
    }

    protected boolean isNeedToGrow(ByteBuffer src) {
        return buffer.remaining() < src.remaining();
    }

    protected void grow(ByteBuffer src) {
        ByteBuffer newbuf = ByteBuffer.allocate(buffer.capacity() + getGrowCapacityCount(src) * initCapacity);
        buffer.flip();
        newbuf.put(buffer);
        buffer = newbuf;
    }

    protected int getGrowCapacityCount(ByteBuffer src) {
        return (int) Math.ceil((float) (src.remaining() - buffer.remaining()) / (float) initCapacity);
    }

    public ByteBuffer compact() {
        return buffer.compact();
    }

    public int capacity() {
        return buffer.capacity();
    }

    public int position() {
        return buffer.position();
    }

    public Buffer position(int newPosition) {
        return buffer.position(newPosition);
    }

    public int limit() {
        return buffer.limit();
    }

    public Buffer limit(int newLimit) {
        return buffer.limit(newLimit);
    }

    public Buffer mark() {
        return buffer.mark();
    }

    public Buffer reset() {
        return buffer.reset();
    }

    public Buffer clear() {
        return buffer.clear();
    }

    public Buffer flip() {
        return buffer.flip();
    }

    public Buffer rewind() {
        return buffer.rewind();
    }

    public int remaining() {
        return buffer.remaining();
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    public byte[] array() {
        return buffer.array();
    }

    public int putTo(ByteBuffer dst) {
        int n;
        if (dst.remaining() < buffer.remaining()) {
            n = dst.remaining();
            int limit = buffer.limit();
            buffer.limit(buffer.position() + dst.remaining());
            dst.put(buffer);
            buffer.limit(limit);
        } else {
            n = buffer.remaining();
            dst.put(buffer);
        }

        return n;
    }

    // like compact but without byte copy
    public Buffer unflip() {
        buffer.position(buffer.limit());
        buffer.limit(buffer.capacity());
        return buffer;
    }

    public ByteBuffer original() {
        return buffer;
    }
}
