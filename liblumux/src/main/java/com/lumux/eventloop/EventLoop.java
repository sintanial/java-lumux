package com.lumux.eventloop;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.AbstractSelectableChannel;

public interface EventLoop {
    SelectionKey register(AbstractSelectableChannel channel, int opts, KeyHandler handler) throws ClosedChannelException;

    void addTickHandler(TickHandler handler) throws ClosedChannelException;

    void rmTickHandler(TickHandler handler) throws ClosedChannelException;

    void spawn(Runnable runnable) throws ClosedChannelException;

    interface KeyHandler {
        void handle(SelectionKey key);
    }

    interface TickHandler {
        void handle();
    }
}
