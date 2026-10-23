package com.github.stjiege.ftpshare.filesystem;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.LogManager;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Global caps for FTP network transfers. Local moves use MoveSlots instead.
 * TransferCommandGuard acquires before the command opens any file; LIST/NLST/MLSD use the
 * separate {@link #LISTINGS} gate, not the transfer slots.
 */
public final class TransferSlots {

    /** Immutable point-in-time view of the active transfer gate. */
    public static final class SlotSnapshot {
        public final int inUse;
        public final int capacity;

        private SlotSnapshot(int inUse, int capacity) {
            this.inUse = inUse;
            this.capacity = capacity;
        }
    }

    /**
     * How long a transfer worker may block waiting for a transfer slot. While waiting, the worker
     * cannot serve other transfers, so this must fail fast: the client receives a clean 4xx and
     * may retry, instead of 30s of worker starvation that used to stall concurrent transfers.
     * Control commands (USER/PASS/QUIT and so on) run on MINA I/O threads, not this pool, so new
     * logins are unaffected by this wait.
     */
    private static final long ACQUIRE_TIMEOUT_MS = 3_000L;

    /**
     * A fair semaphore whose total permit count can be changed at runtime.
     *
     * {@link Semaphore#reducePermits(int)} is protected, hence the subclass. Shrinking while
     * permits are checked out is safe and is exactly the wanted semantic: the total drops
     * immediately, and the semaphore stays negative until enough in-flight transfers return, so
     * the reduced concurrency takes effect as running transfers finish rather than aborting them.
     */
    private static final class ResizableSemaphore extends Semaphore {
        private int capacity;

        ResizableSemaphore(int capacity) {
            super(capacity, true);
            this.capacity = capacity;
        }

        synchronized void resize(int newCapacity) {
            if (newCapacity == capacity) return;
            if (newCapacity > capacity) {
                release(newCapacity - capacity);
            } else {
                reducePermits(capacity - newCapacity);
            }
            capacity = newCapacity;
        }

        synchronized int capacity() {
            return capacity;
        }

        synchronized SlotSnapshot snapshot() {
            // Negative permits after shrinking represent transfers still finishing.
            return new SlotSnapshot(capacity - availablePermits(), capacity);
        }
    }

    private static final ResizableSemaphore SLOTS =
            new ResizableSemaphore(Constants.FTPConsts.FTP_MAX_CONCURRENT_TRANSFERS);

    /** Hashing is CPU-heavy, so admit at most one file hash without consuming transfer slots. */
    private static final Semaphore HASHES = new Semaphore(1, true);

    /**
     * 目录列举（LIST/NLST/MLSD）的独立闸门。
     *
     * <p>列举刻意不与文件传输争 {@link #SLOTS}——浏览目录不该因为正在传文件就被拒绝。
     * 但它们同样占用传输池的线程：被动模式下一次列举最长要在 accept() 上等满
     * {@code idleTime}（默认 60 秒，见 {@code IODataConnectionFactory}）。没有闸门时，
     * 若干客户端同时列举就能把池线程占满，真正的传输只能在队列里排队，最终以 421 断开告终。</p>
     *
     * <p>容量取「池容量 − 传输槽」：两条路径互补，加起来不超过池容量，且互不抢占。
     * 池容量与 {@code FtpService.createTransferExecutor()} 的写法保持一致（实际槽数 + 余量）。</p>
     */
    private static final ResizableSemaphore LISTINGS =
            new ResizableSemaphore(listingCapacity(Constants.FTPConsts.FTP_MAX_CONCURRENT_TRANSFERS));

    private static int listingCapacity(int slots) {
        int pool = slots + Constants.FTPConsts.FTP_TRANSFER_POOL_HEADROOM;
        return Math.max(1, pool - slots);
    }

    private TransferSlots() {
    }

    /** Current acquired slots and effective capacity; waiting requests do not own slots. */
    public static SlotSnapshot snapshot() {
        return SLOTS.snapshot();
    }

    /**
     * Applies the configured cap. Called once per service start, at a point where no transfer can
     * be in flight, so resizing can never race with a running transfer's release.
     */
    public static void configure(int slots) {
        int bounded = Math.max(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MIN,
                Math.min(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MAX, slots));
        SLOTS.resize(bounded);
        LISTINGS.resize(listingCapacity(bounded));
        LogManager.info("FTP并发传输槽 " + bounded);
    }

    /**
     * Blocks briefly for a slot and returns normally, or throws so the caller fails the transfer
     * cleanly. There is no "not acquired" return path, so a successful call always owes a
     * matching {@link #release()}.
     */
    public static void acquire() throws IOException {
        try {
            if (SLOTS.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            LogManager.warning("并发传输已满（" + SLOTS.capacity() + "），等待超时，拒绝新的数据传输");
            throw new IOException("Too many concurrent transfers");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Transfer interrupted", error);
        }
    }

    public static void release() {
        SLOTS.release();
    }

    /** Fails immediately when another full-file hash is already running. */
    public static void acquireHash() throws IOException {
        if (!HASHES.tryAcquire()) {
            LogManager.warning("文件哈希已在计算中，拒绝并发 HASH 请求");
            throw new IOException("Hash capacity unavailable");
        }
    }

    public static void releaseHash() {
        HASHES.release();
    }

    /** Same contract as {@link #acquire()} for the directory-listing gate. */
    public static void acquireListing() throws IOException {
        try {
            if (LISTINGS.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
            LogManager.warning("并发目录列举已满（" + LISTINGS.capacity() + "），等待超时，拒绝新的列举");
            throw new IOException("Too many concurrent directory listings");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Listing interrupted", error);
        }
    }

    public static void releaseListing() {
        LISTINGS.release();
    }
}
