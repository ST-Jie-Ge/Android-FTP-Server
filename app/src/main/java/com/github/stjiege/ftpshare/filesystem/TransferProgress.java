package com.github.stjiege.ftpshare.filesystem;

/** Transfer-local accounting; publish aggregate progress without coupling each block to session state. */
public final class TransferProgress {
    public interface Reporter { void report(int bytes); }
    interface Clock { long nanos(); }
    private static final int BATCH_BYTES = 1024 * 1024;
    private static final long INTERVAL_NANOS = 250_000_000L;
    private final Reporter reporter;
    private final Clock clock;
    private long pending;
    private long lastReport;

    public TransferProgress(Reporter reporter) { this(reporter, System::nanoTime); }
    TransferProgress(Reporter reporter, Clock clock) {
        this.reporter = reporter;
        this.clock = clock;
        lastReport = clock.nanos();
    }
    public void add(int bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative progress");
        pending += bytes;
        long now = clock.nanos();
        if (pending >= BATCH_BYTES || now - lastReport >= INTERVAL_NANOS) {
            flush();
            lastReport = now;
        }
    }
    /** Call on success and failure, before clearing transfer-active state. */
    public void flush() {
        while (pending > 0) {
            int count = (int) Math.min(pending, Integer.MAX_VALUE);
            reporter.report(count);
            pending -= count;
        }
    }
}
