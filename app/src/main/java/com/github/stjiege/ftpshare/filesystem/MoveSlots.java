package com.github.stjiege.ftpshare.filesystem;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Local moves have their own nonblocking admission gate. */
public final class MoveSlots {
    private static final Semaphore SLOTS = new Semaphore(CopyExecutor.threadCount(), true);
    public static boolean acquire() { return SLOTS.tryAcquire(); }
    public static void release() { SLOTS.release(); }

    /**
     * Temporarily excludes every move while the stopped-service sweeper removes abandoned
     * staging paths. Holding all permits prevents the sweep from racing a live copy.
     */
    public static boolean acquireForSweep(long timeoutMillis) throws InterruptedException {
        return SLOTS.tryAcquire(CopyExecutor.threadCount(), timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public static void releaseAfterSweep() {
        SLOTS.release(CopyExecutor.threadCount());
    }
    private MoveSlots() { }
}
