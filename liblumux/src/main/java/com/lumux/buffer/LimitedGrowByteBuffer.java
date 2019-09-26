package com.lumux.buffer;

import java.nio.ByteBuffer;

public class LimitedGrowByteBuffer extends AutoGrowByteBuffer {
    private int maxSize;
    private int maxGrowSteps;
    private int curGrowSteps;

    public LimitedGrowByteBuffer(int capacity, int maxGrowSteps, int maxSize) {
        super(capacity);
        this.maxGrowSteps = maxGrowSteps;
        this.maxSize = maxSize;
    }

    protected boolean isCanGrow() {
        return curGrowSteps < maxGrowSteps && original().capacity() < maxSize;
    }

    @Override
    public boolean put(ByteBuffer src) {
        if (!isCanGrow()) {
            return false;
        }
        if (isNeedToGrow(src)) {
            curGrowSteps += getGrowCapacityCount(src);
        }

        return super.put(src);
    }
}
