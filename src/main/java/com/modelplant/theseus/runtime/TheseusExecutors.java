package com.modelplant.theseus.runtime;

import com.modelplant.theseus.Theseus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class TheseusExecutors {
    private static ExecutorService WORKERS;

    private TheseusExecutors() {
    }

    public static synchronized void init() {
        if (WORKERS != null) {
            return;
        }
        AtomicInteger idx = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "theseus-worker-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        WORKERS = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), factory);
        Theseus.LOGGER.info("Theseus worker pool started");
    }

    public static ExecutorService workers() {
        if (WORKERS == null) {
            init();
        }
        return WORKERS;
    }

    public static synchronized void shutdown() {
        if (WORKERS != null) {
            WORKERS.shutdownNow();
            WORKERS = null;
        }
    }
}
