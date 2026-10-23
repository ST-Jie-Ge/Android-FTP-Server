package com.github.stjiege.ftpshare.filesystem;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跨卷移动（服务端本地拷贝）专用的线程池。
 *
 * <p>为什么必须有专用车道，而不是直接复用传输池：
 * 跨卷拷贝是<b>分钟级</b>的操作（1 GB 文件在机身存储与 TF 卡之间搬运要走完整个文件大小，
 * 没有超时、没有上限），而传输池的容量是按"槽 + 4 条余量"算出来的，设计目标是<b>秒级</b>的
 * 文件传输与目录列举。把分钟级任务放进秒级池，等于让一次移动长期占住一条本该服务传输的线程，
 * 高负载下几条移动就能把池抽空，表现是"传不动"而不是"移动慢"。</p>
 *
 * <p>为什么不能留在调用方线程上执行（这是修它之前的实际行为）：{@code RNFR/RNTO} 不在
 * {@code DefaultFtpHandler} 的传输命令名单里，会 inline 跑在 <b>MINA 的 I/O 线程</b>上。
 * 而每个 {@code IoProcessor} 只有一条线程，且每个会话固定绑定其中一条
 * （{@code SimpleIoProcessorPool.getProcessor} 按 sessionId 取模后写进会话属性）。
 * processor 主循环一旦被拷贝堵住，那条线程上的<b>全体会话既读不到命令也发不出应答</b>
 * ——已经传完的数据连接都发不出最终的 226。这比"命令变慢"严重一个量级。</p>
 *
 * <p>线程数为什么是这个数：文件拷贝是存储 I/O 密集而非 CPU 密集，堆线程换不来并行带宽，
 * 只会在 NAND / TF 卡上制造随机写竞争。这里给的是"同时允许多少次移动"，取 CPU 核数的一半、
 * 上下夹在 2–4，既让多个客户端的并发移动真能并行（这才是多线程性能该落在的地方），
 * 又不至于把闪存通道打爆。线程闲置 60 秒自动回收，常驻零开销。</p>
 */
public final class CopyExecutor {

    private static final int KEEP_ALIVE_SECONDS = 60;
    private static final int MIN_THREADS = 2;
    private static final int MAX_THREADS = 4;
    private static final int QUEUE_CAPACITY = 64;

    private static final ExecutorService INSTANCE = create();

    private CopyExecutor() {
    }

    /** Process-wide pool; daemon threads with idle timeout, so it never needs shutting down. */
    public static ExecutorService get() {
        return INSTANCE;
    }

    /** 线程数的取值规则：核数一半，夹在 [2, 4]。 */
    static int threadCount() {
        int processors = Runtime.getRuntime().availableProcessors();
        return Math.max(MIN_THREADS, Math.min(MAX_THREADS, processors / 2));
    }

    private static ExecutorService create() {
        int size = threadCount();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                size, size, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                new CopyThreadFactory(size),
                new ThreadPoolExecutor.AbortPolicy());
        // Core threads must be allowed to die, otherwise every device keeps idle copy
        // threads alive for the lifetime of the process even when nothing is ever moved.
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private static final class CopyThreadFactory implements java.util.concurrent.ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final int size;
        CopyThreadFactory(int size) { this.size = size; }
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "FTP-Copy-" + nextId.getAndIncrement() + "/" + size);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
