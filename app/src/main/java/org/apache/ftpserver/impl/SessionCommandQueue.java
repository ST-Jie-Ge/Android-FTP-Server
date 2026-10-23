package org.apache.ftpserver.impl;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * One bounded FIFO per control session. Queue locks never cover command execution.
 *
 * <p>A command may declare a <em>lane</em> — the executor it must run on. Heavy work
 * (transfers, listings, cross-volume moves) declares a lane so it never occupies MINA's I/O
 * threads; {@code null} means "run on whoever currently owns the queue", which is how cheap
 * control commands stay cheap. Ordering is untouched: a lane switch only transfers ownership,
 * it never lets a later command overtake an earlier one.</p>
 */
final class SessionCommandQueue {
    private static final int MAX_PENDING = 32;
    /** Bounded cancellation headroom lets ABOR enter a saturated session queue. */
    private static final int MAX_RESERVED_CANCELLATIONS = MAX_PENDING;
    private final Executor executor;
    private final ArrayDeque<Entry> pending = new ArrayDeque<>();
    private Drain owner;
    private boolean closed;
    private int reservedCancellations;
    private long transferGeneration;

    SessionCommandQueue(Executor executor) { this.executor = executor; }

    synchronized long transferGeneration() { return transferGeneration; }

    /**
     * 取消排队中的传输。
     *
     * <p>被生成号作废的排队传输只会发一句 425，可以清掉它们的车道标记，避免占用传输池。
     * 其他命令（尤其是可能阻塞在存储层的文件系统命令）必须保留原车道。</p>
     */
    synchronized int cancelTransfers() {
        transferGeneration++;
        int invalidated = 0;
        // Only commands whose generation guard will reject them can safely run inline.
        // Keep filesystem metadata commands on their worker lane; FUSE may block.
        for (Entry entry : pending) {
            if (entry.cancelOnTransferGeneration && entry.lane != null) {
                entry.lane = null;
                invalidated++;
            }
        }
        return invalidated;
    }

    private static final class Entry {
        final Runnable task;
        final boolean cancelOnTransferGeneration;
        final boolean reservedCancellation;
        /** A cancelled transfer can reply inline; other worker lanes must be preserved. */
        Executor lane;
        Entry(Runnable task, Executor lane, boolean cancelOnTransferGeneration,
                boolean reservedCancellation) {
            this.task = task;
            this.lane = lane;
            this.cancelOnTransferGeneration = cancelOnTransferGeneration;
            this.reservedCancellation = reservedCancellation;
        }
    }

    void execute(Runnable task, boolean offload) {
        enqueue(task, offload ? executor : null, false, false);
    }

    void execute(Runnable task, boolean offload, boolean cancellation) {
        enqueue(task, offload ? executor : null, cancellation, false);
    }

    /** Routes one command to a specific executor; {@code null} runs it on the owning thread. */
    void execute(Runnable task, Executor lane, boolean cancellation) {
        enqueue(task, lane, cancellation, false);
    }

    /** Routes a command and marks whether ABOR invalidates it before it starts. */
    void execute(Runnable task, Executor lane, boolean cancellation,
            boolean cancelOnTransferGeneration) {
        enqueue(task, lane, cancellation, cancelOnTransferGeneration);
    }

    synchronized boolean isAtCapacity() { return pending.size() >= MAX_PENDING; }

    private void enqueue(Runnable task, Executor lane, boolean cancellation,
            boolean cancelOnTransferGeneration) {
        Drain next;
        synchronized (this) {
            boolean reservedCancellation = false;
            if (pending.size() >= MAX_PENDING) {
                if (!cancellation || reservedCancellations >= MAX_RESERVED_CANCELLATIONS) {
                    throw new RejectedExecutionException("FTP session queue unavailable");
                }
                reservedCancellation = true;
            }
            if (closed) {
                throw new RejectedExecutionException("FTP session queue unavailable");
            }
            pending.addLast(new Entry(task, lane, cancelOnTransferGeneration,
                    reservedCancellation));
            if (reservedCancellation) reservedCancellations++;
            if (owner != null) {
                if (!cancellation || owner.started) return;
                // Revoke a not-yet-started worker; it must not execute even if
                // the executor has just dequeued it. Running commands never yield ownership.
                if (owner.lane instanceof ThreadPoolExecutor) {
                    ((ThreadPoolExecutor) owner.lane).remove(owner);
                }
            }
            next = new Drain(lane);
            owner = next;
        }
        dispatch(next);
    }

    private void dispatch(Drain next) {
        if (next.lane != null) {
            try {
                next.lane.execute(next);
            } catch (RejectedExecutionException rejected) {
                close();
                throw rejected;
            }
        } else {
            next.run();
        }
    }

    synchronized void close() {
        closed = true;
        pending.clear();
        reservedCancellations = 0;
    }

    private final class Drain implements Runnable {
        final Executor lane;
        boolean started;
        Drain(Executor lane) { this.lane = lane; }

        @Override public void run() {
        synchronized (SessionCommandQueue.this) {
            if (owner != this || closed) return;
            started = true;
        }
        try {
            while (true) {
                Entry entry;
                Drain successor = null;
                synchronized (SessionCommandQueue.this) {
                    entry = closed ? null : pending.peekFirst();
                    if (entry == null) {
                        owner = null;
                        return;
                    }
                    // Hand the queue over to the lane this command belongs to. Commands
                    // received after it are picked up by that successor, in receipt order.
                    if (entry.lane != null && entry.lane != lane) {
                        successor = new Drain(entry.lane);
                        owner = successor;
                    } else {
                        pending.removeFirst();
                        if (entry.reservedCancellation) reservedCancellations--;
                    }
                }
                if (successor != null) { dispatch(successor); return; }
                entry.task.run();
            }
        } catch (RuntimeException | Error failure) {
            close();
            synchronized (SessionCommandQueue.this) { owner = null; }
            throw failure;
        }
        }
    }
}
