package com.lumux;

import com.lumux.exception.FrameTooLargeException;

import java.io.IOException;
import java.nio.ByteBuffer;

public class FrameReader {
    private ByteBuffer buffer;

    private short cmd;
    private int len;
    private int sid;

    private boolean isHeaderReaded;

    FrameReader(int frameSize) {
        // todo: пересчетать размер буффера
        this.buffer = ByteBuffer.allocate(2 * (frameSize + 2 * Frame.HEADER_SIZE));
    }

    void put(ByteBuffer buffer) {
        this.buffer.put(buffer);
    }

    boolean read(FrameHandler handler) throws IOException {
        if (buffer.position() < Frame.HEADER_SIZE) {
            return false;
        }

        if (!isHeaderReaded) {
            buffer.flip();

            cmd = (short) (buffer.get() & 0xFF);
            // cast to unsigned & 0xFFFFFFFFL is unnecessary
            len = buffer.getInt();
            // cast to unsigned & 0xFFFFFFFFL is unnecessary
            sid = buffer.getInt();

            if (len > buffer.capacity()) {
                throw new FrameTooLargeException();
            }

            isHeaderReaded = true;

            buffer.compact();
        }

        // ждём когда накопится достаточно данных в буффере что бы можно было считать Frame Payload
        if (buffer.position() < len) {
            return false;
        }

        if (len > 0) {
            buffer.flip();

            handler.handle(cmd, len, sid, buffer.duplicate());

            buffer.position(len);
            buffer.compact();
        } else {
            handler.handle(cmd, len, sid, null);
        }

        isHeaderReaded = false;

        return true;
    }

    interface FrameHandler {
        void handle(short cmd, int len, int sid, ByteBuffer data) throws IOException;
    }
}