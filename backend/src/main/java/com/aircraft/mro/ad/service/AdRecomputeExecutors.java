package com.aircraft.mro.ad.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 适航指令重算专用执行器：单线程、守护线程、CallerRuns 兜底。
 *
 * <p>单线程串行是刻意的——批量任务之间不并发，配合每架飞机一个 REQUIRES_NEW 短事务，
 * 既不会把连接池占满，也不会出现两个大批量任务同时压住飞机行锁；
 * 普通查询和飞行登记走各自的事务，不排队等这个线程。
 */
public final class AdRecomputeExecutors {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "ad-recompute-" + seq.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private AdRecomputeExecutors() {
    }

    public static void run(Runnable task) {
        EXECUTOR.execute(task);
    }
}
