package com.lumux;

import java.nio.ByteBuffer;

public class PriorityFrame extends Frame implements Comparable<PriorityFrame> {
    int priority;
    int sequence;

    PriorityFrame(byte cmd, int sid, int priority) {
        super(cmd, sid);
        this.priority = priority;
    }

    PriorityFrame(byte cmd, int sid, int priority, ByteBuffer data) {
        super(cmd, sid, data);
        this.priority = priority;
    }

    public PriorityFrame(byte cmd, int sid, int priority, byte[] data) {
        super(cmd, sid, data);
        this.priority = priority;
    }

    @Override
    public int compareTo(PriorityFrame f) {
        if (priority == f.priority) {
            return sequence - f.sequence;
        }
        return priority - f.priority;
    }
}
