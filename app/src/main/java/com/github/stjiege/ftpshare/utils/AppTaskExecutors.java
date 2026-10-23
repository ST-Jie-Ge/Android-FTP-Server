package com.github.stjiege.ftpshare.utils;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small, bounded executor for directory scans triggered while users choose a shared path.
 */
public final class AppTaskExecutors {
    private static final int DIRECTORY_SCAN_QUEUE_CAPACITY = 4;
    private static final ThreadPoolExecutor DIRECTORY_SCAN_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(DIRECTORY_SCAN_QUEUE_CAPACITY),
            new NamedThreadFactory("Directory-Scan"), new ThreadPoolExecutor.DiscardOldestPolicy());

    private AppTaskExecutors() { }

    /**
     * Serializes directory enumeration and applies a small bound when a user changes paths quickly.
     *
     * <p>When the queue is full the oldest pending scan is dropped instead of rejecting the newest
     * one: a discarded old directory is exactly what the user no longer wants to see, whereas
     * rejecting the newest would leave the list showing a directory they did navigate away from.
     */
    public static boolean executeDirectoryScan(Runnable task) {
        try {
            DIRECTORY_SCAN_EXECUTOR.execute(task);
            return true;
        } catch (RejectedExecutionException error) {
            LogManager.warning("目录扫描队列已满，已忽略过期的目录读取请求");
            return false;
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger nextId = new AtomicInteger(1);

        NamedThreadFactory(String prefix) { this.prefix = prefix; }

        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + "-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
