package com.github.stjiege.ftpshare.ftpserver.data;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounds blocking socket writes, including TLS close and MODE Z's final bytes. */
public final class TimedSocketOutputStream extends OutputStream {
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(2, task -> {
        Thread thread = new Thread(task, "ftp-write-timeout");
        thread.setDaemon(true);
        return thread;
    });
    static { WATCHDOG.setRemoveOnCancelPolicy(true); }

    private final Socket socket;
    private final OutputStream output;
    private final long timeoutNanos;
    private final ScheduledFuture<?> watch;
    private long started;
    private boolean pending;
    private boolean expired;
    private boolean closed;

    public TimedSocketOutputStream(Socket socket) throws IOException {
        this.socket = socket;
        output = socket.getOutputStream();
        int timeoutMillis = socket.getSoTimeout();
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long period = Math.max(1, Math.min(1000, timeoutMillis));
        watch = timeoutMillis > 0
                ? WATCHDOG.scheduleWithFixedDelay(this::checkTimeout, period, period, TimeUnit.MILLISECONDS)
                : null;
    }

    private void checkTimeout() {
        synchronized (this) {
            if (closed || expired || !pending || System.nanoTime() - started < timeoutNanos) return;
            expired = true;
        }
        // Close this exact socket, never the session's possibly newer data connection.
        try { socket.close(); } catch (IOException ignored) { }
    }

    private synchronized void begin() throws IOException {
        if (expired) throw new SocketTimeoutException("Data connection write timed out");
        if (closed) throw new IOException("Data output closed");
        started = System.nanoTime();
        pending = true;
    }

    private synchronized void end() throws IOException {
        pending = false;
        if (expired) throw new SocketTimeoutException("Data connection write timed out");
    }

    @Override public void write(int value) throws IOException {
        begin();
        try { output.write(value); } finally { end(); }
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        // A large directory must make incremental progress rather than share one total deadline.
        while (length > 0) {
            int count = Math.min(length, 16 * 1024);
            begin();
            try { output.write(bytes, offset, count); } finally { end(); }
            offset += count;
            length -= count;
        }
    }

    @Override public void flush() throws IOException {
        begin();
        try { output.flush(); } finally { end(); }
    }

    @Override public void close() throws IOException {
        synchronized (this) { if (closed) return; }
        try {
            begin();
            try { output.close(); } finally { end(); }
        } finally {
            stopWatching();
        }
    }

    /** Also called by the transfer owner if compression fails before closing its delegate. */
    public void stopWatching() {
        synchronized (this) { closed = true; pending = false; }
        if (watch != null) watch.cancel(false);
    }
}
