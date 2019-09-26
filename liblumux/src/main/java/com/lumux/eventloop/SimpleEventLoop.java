package com.lumux.eventloop;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SimpleEventLoop implements EventLoop {
    private Selector selector;
    private ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private int tick;
    private List<TickHandler> tickHandlers;

    /**
     * @param tick in milliseconds
     */
    public SimpleEventLoop(int tick) throws IOException {
        this.selector = Selector.open();
        this.tick = tick;
    }

    public synchronized void spawn(Runnable runnable) throws ClosedChannelException {
        if (!selector.isOpen()) throw new ClosedChannelException();

        tasks.add(runnable);
        selector.wakeup();
    }

    public void close() throws ClosedChannelException {
        spawn(new Runnable() {
            @Override
            public void run() {
                Set<SelectionKey> keys = selector.keys();
                for (SelectionKey key : keys) {
                    key.cancel();
                    processKey(key);
                }

                try {
                    selector.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    public SelectionKey register(AbstractSelectableChannel channel, int opts, KeyHandler handler) throws ClosedChannelException {
        return channel.register(selector, opts, handler);
    }

    public void addTickHandler(final TickHandler handler) throws ClosedChannelException {
        spawn(new Runnable() {
            @Override
            public void run() {
                if (tickHandlers == null) {
                    tickHandlers = new ArrayList<>();
                }
                tickHandlers.add(handler);
            }
        });
    }

    public void rmTickHandler(final TickHandler handler) throws ClosedChannelException {
        spawn(new Runnable() {
            @Override
            public void run() {
                if (tickHandlers == null) {
                    return;
                }
                tickHandlers.remove(handler);
            }
        });
    }

    public void listen() throws IOException {
        if (!selector.isOpen()) return;

        try {
            loop();
        } finally {
            this.tasks.clear();
        }
    }

    private void loop() throws IOException {
        while (selector.isOpen()) {
            int selected;

            Runnable runnable = tasks.poll();
            if (runnable != null) {
                runnable.run();

                selected = selector.selectNow();
            } else {
                selected = selector.select(this.tick);
            }

            // если в эвентлупе нету задач, останавливаем эвентлуп
            if (selector.keys().size() == 0 && tasks.size() == 0) {
                selector.close();
                break;
            }

            if (tickHandlers != null) {
                for (TickHandler tickerHandler : tickHandlers) {
                    tickerHandler.handle();
                }
            }

            if (selected == 0) continue;

            Set<SelectionKey> keys = selector.selectedKeys();
            for (SelectionKey key : keys) {
                processKey(key);
            }
        }
    }

    private void processKey(SelectionKey key) {
        Object attachment = key.attachment();
        if (attachment instanceof KeyHandler) {
            ((KeyHandler) attachment).handle(key);
        }
    }
}
