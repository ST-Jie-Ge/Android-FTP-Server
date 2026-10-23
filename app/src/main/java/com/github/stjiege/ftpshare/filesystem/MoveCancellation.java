package com.github.stjiege.ftpshare.filesystem;

import java.io.InterruptedIOException;

/** Cooperative cancellation; never interrupt a pooled thread that may already serve a new task. */
public final class MoveCancellation {
    public interface Check { boolean cancelled(); }
    private static final ThreadLocal<Check> CURRENT = new ThreadLocal<>();
    public static void bind(Check check) { CURRENT.set(check); }
    public static void clear() { CURRENT.remove(); }
    static void check() throws InterruptedIOException {
        Check check = CURRENT.get();
        if (Thread.currentThread().isInterrupted() || (check != null && check.cancelled())) {
            throw new InterruptedIOException("Move cancelled");
        }
    }

    /**
     * Non-throwing cancellation probe for command layers that must decide commit vs rollback
     * without unwinding. Reads only the bound check, not the thread interrupt flag — the transfer
     * lane is a pooled thread whose interrupt bit may be stale, whereas the bound check reflects
     * whether ABOR has actually arrived for this transfer.
     */
    public static boolean isCancelled() {
        Check check = CURRENT.get();
        return check != null && check.cancelled();
    }
    private MoveCancellation() { }
}
