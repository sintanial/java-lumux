package com.lumux;

import java.nio.ByteBuffer;
import java.util.Arrays;

class Frame {
    static final byte CMD_SYN = 0;
    static final byte CMD_FIN = 1;
    static final byte CMD_PSH = 2;
    static final byte CMD_WIN = 3;
    static final byte CMD_NOP = 4;

    static final int HEADER_SIZE = 9;

    short cmd;
    int sid;
    ByteBuffer data;

    // using for avoid allocate ByteBuffer for small chunk of bytes
    byte[] smallData;

    Frame(short cmd, int sid) {
        this.cmd = cmd;
        this.sid = sid;
    }

    Frame(short cmd, int sid, ByteBuffer data) {
        this.cmd = cmd;
        this.sid = sid;
        this.data = data;
    }

    Frame(byte cmd, int sid, byte[] data) {
        this(cmd, sid);
        this.smallData = data;
    }

    @Override
    public String toString() {
        String scmd = "cmd=" + cmd + " (";
        switch (cmd) {
            case CMD_SYN:
                scmd += "SYN";
                break;
            case CMD_FIN:
                scmd += "FIN";
                break;
            case CMD_PSH:
                scmd += "PSH";
                break;
            case CMD_WIN:
                scmd += "WIN";
                break;
            case CMD_NOP:
                scmd += "NOP";
                break;
        }
        scmd += ")";

        return "Frame{" + scmd +
                ", sid=" + sid +
                ", data=" + data +
                ", smallData=" + Arrays.toString(smallData) +
                '}';
    }
}
