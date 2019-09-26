package com.lumux;

public class Config {
    public boolean keepAliveEnabled = true;
    // in millis
    public int keepAliveInterval = 15_000;
    // in millis
    public int keepAliveTimeout = 20_000;

    public int maxFrameSize = 16 * 1024;

    public int maxStreamWindowSize = 16 * 1024;

    public int streamAcceptBacklogSize = 0;
}
