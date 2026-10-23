package com.github.stjiege.ftpshare.utils;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.Level;
import org.apache.log4j.spi.LoggingEvent;
import org.apache.log4j.spi.ThrowableInformation;

/**
 * Forwards WARN-and-above events from the Apache FtpServer / MINA dependency chain
 * into the application's own {@link LogManager}, so they survive in release builds.
 *
 * <p>Why this exists: those jars log through SLF4J → log4j, and
 * {@link MyApplication} only attached {@link Log4jLogcatAppender} under
 * {@code BuildConfig.DEBUG}. In a release build log4j therefore had no appender at
 * all and every internal diagnostic was dropped — including MINA's
 * {@code DefaultExceptionMonitor}, which reports the uncaught I/O-processor
 * exceptions behind most "FTP stalled / connection dropped" reports. Those never
 * reached the log files users can export.</p>
 *
 * <p>Behaviour:</p>
 * <ul>
 *   <li><b>WARN threshold</b> — installed with {@code setThreshold(Level.WARN)};
 *       DEBUG/INFO from the jars is developer chatter and would bury real entries.</li>
 *   <li><b>Non-blocking</b> — {@code append()} runs on whatever thread logged
 *       (MINA I/O threads included). It only offers to LogManager's bounded queue,
 *       never writes a file or touches the main thread.</li>
 *   <li><b>De-duplicated per error type</b> — one client cancelling a 4-connection batch
 *       download produced 17 identical {@code ConnectionResetException} entries in two
 *       seconds, which is 17 × 17 lines of stack trace for a single user action. Within a
 *       {@link #WINDOW_MS one-minute window}, an event whose key (message + throwable class)
 *       has already been recorded is counted and dropped — <em>whether or not the repeats are
 *       consecutive</em>. A type that occurs only once is never dropped.</li>
 *   <li><b>No cap on distinct types</b> — the previous implementation also dropped every
 *       event after the 20th <em>distinct</em> one in a window, so a busy minute silently
 *       swallowed whole error types. That cap is gone: every distinct type is reported.
 *       Memory is bounded instead by {@link #MAX_TRACKED_KEYS}, an LRU over the tracked keys
 *       — once it is full the oldest key is forgotten rather than new ones being rejected.</li>
 *   <li><b>Aggregated counts</b> — suppressed repeats are counted and reported as one concise
 *       line every {@link #REPORT_EVERY_SUPPRESSED} of them, plus the remainder when the
 *       window rolls. The counts describe the repeats that were folded, not a type quota.</li>
 *   <li><b>Stack traces trimmed</b> — the tail of these traces is always
 *       {@code ThreadPoolExecutor.runWorker} / {@code Thread.run}, i.e. fixed noise.
 *       Keeping the first {@link #MAX_STACK_FRAMES} frames preserves the "which class
 *       threw" information that makes the entry worth keeping.</li>
 *   <li><b>Vanished sockets are not errors</b> — a client that cancels a download makes
 *       the socket blow up in at least two places at once (data channel in
 *       {@code IODataConnection}, then control channel in MINA's filter chain and
 *       {@code DefaultFtpHandler}). They are collapsed to one WARNING line without a stack
 *       trace. The wording says "连接中断", not "客户端断开", because a reset is equally
 *       consistent with a cancel, a client crash, a Wi-Fi drop or an expired NAT flow —
 *       the socket alone cannot tell you which.</li>
 *   <li><b>Timeouts are the opposite case and stay loud</b> — {@code SocketTimeoutException}
 *       is our own {@code SO_TIMEOUT} (60 s on the data socket) expiring, i.e. the server
 *       gave up waiting. That is evidence of a stall, not of a clean exit, so it keeps its
 *       stack trace and its ERROR level.</li>
 * </ul>
 */
public final class Log4jLogManagerAppender extends AppenderSkeleton {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_MESSAGE_CHARS = 512;
    private static final int MAX_STACK_FRAMES = 8;
    /**
     * 每个窗口内最多跟踪多少种不同的错误。这不是"每分钟条数上限"——所有错误类型都会被
     * 输出；它只是给去重表封顶，防止病态 logger 用无限种不同的文案把内存吃满。表满时
     * 淘汰最久未出现的键（LinkedHashMap 插入序当 LRU 用），新类型因此永远进得来。
     */
    private static final int MAX_TRACKED_KEYS = 128;
    /**
     * 被折叠掉的重复攒够这么多条，就补一行汇总。取 20：一次批量取消下载的量级正好在这附近，
     * 再小会让汇总行本身变成噪音，再大则长洪水期间看不到任何反馈。
     */
    private static final int REPORT_EVERY_SUPPRESSED = 20;

    private static final Object WINDOW_LOCK = new Object();
    private static long windowStart;
    /** 本窗口内被折叠的重复条数（尚未汇总输出的部分）。 */
    private static int suppressed;
    /** 键 → 本窗口已输出次数。插入序即 LRU 序。 */
    private static final java.util.Map<String, Integer> SEEN =
            new java.util.LinkedHashMap<String, Integer>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Integer> eldest) {
                    return size() > MAX_TRACKED_KEYS;
                }
            };

    @Override
    protected void append(LoggingEvent event) {
        if (event == null || !LogManager.isLoggingEnabled()) return;

        Level level = event.getLevel();
        if (level == null || !level.isGreaterOrEqual(Level.WARN)) return;

        String message = truncate(String.valueOf(event.getRenderedMessage()));
        String line = "内部 " + level + "：" + message;

        Throwable cause = null;
        ThrowableInformation info = event.getThrowableInformation();
        if (info != null) cause = info.getThrowable();

        // Both channels of one cancelled transfer report separately; collapse them.
        String disconnect = disconnectReason(cause);
        if (disconnect != null) {
            // No throwable: LogManager.error() would render a stack trace, and a peer that
            // vanished is not a fault we can fix. The wording is deliberately neutral — see
            // disconnectReason(). Pair this line with the lifecycle line FtpLogFtplet writes
            // on disconnect to tell a cancelled download from a dropping connection.
            if (takeSlot("disconnect|" + disconnect)) {
                LogManager.warning("连接中断：" + disconnect);
            }
            return;
        }

        // Our own timeout firing: keep the trace, and say plainly that it is server-side.
        if (isServerSideTimeout(cause)) {
            if (takeSlot("timeout|" + line)) {
                trimStackTrace(cause);
                LogManager.error(line + "（服务端读写超时：数据连接 60 秒无数据，服务端主动中止）", cause);
            }
            return;
        }

        if (cause != null) {
            String key = line + "|" + cause.getClass().getName();
            if (!takeSlot(key)) return;
            trimStackTrace(cause);
        } else if (!takeSlot(line)) {
            return;
        }

        // A stack trace is the valuable half of these events, so route anything
        // carrying one through error() even when log4j ranked it only a warning.
        if (cause != null || level.isGreaterOrEqual(Level.ERROR)) {
            LogManager.error(line, cause);
        } else {
            LogManager.warning(line);
        }
    }

    /**
     * Returns false when the event should be dropped: an exact repeat of a type already
     * recorded in this window. Anything else is allowed through — there is deliberately no
     * per-minute cap on distinct types.
     */
    private boolean takeSlot(String key) {
        int toReport = 0;
        boolean allowed = false;
        synchronized (WINDOW_LOCK) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= WINDOW_MS) {
                // 窗口滚动：把上一窗口的尾数（不足 REPORT_EVERY_SUPPRESSED 的部分）补报掉。
                toReport = suppressed;
                windowStart = now;
                suppressed = 0;
                SEEN.clear();
            }
            if (SEEN.containsKey(key)) {
                // 同一类型本窗口只输出一次，重复的（无论是否连续）只计数。
                SEEN.put(key, SEEN.get(key) + 1);
                suppressed++;
                if (suppressed >= REPORT_EVERY_SUPPRESSED) {
                    toReport = suppressed;
                    suppressed = 0;
                }
            } else {
                // LinkedHashMap 在超过 MAX_TRACKED_KEYS 时自动淘汰最老的键，
                // 新类型因此永远能被跟踪，不会退化成"每来一次都输出一遍"。
                SEEN.put(key, 1);
                allowed = true;
            }
        }
        // Reported outside the lock: doAppend() is synchronized on this appender, so every
        // logging thread in the process is waiting behind us while this runs.
        if (toReport > 0) {
            LogManager.warning("内部日志去重省略 " + toReport + " 条重复");
        }
        return allowed;
    }

    /**
     * Drops the fixed {@code ThreadPoolExecutor.runWorker} / {@code Thread.run} tail.
     * The throwable is already spent by the time a logger sees it, so rewriting its own
     * frames is safe and keeps LogManager's rendering unchanged.
     */
    private static void trimStackTrace(Throwable cause) {
        StackTraceElement[] frames = cause.getStackTrace();
        if (frames != null && frames.length > MAX_STACK_FRAMES) {
            cause.setStackTrace(java.util.Arrays.copyOf(frames, MAX_STACK_FRAMES));
        }
    }

    /**
     * Recognises the exceptions that mean "the socket is gone" — the peer closed it, the
     * process died, or the network dropped. Returns a short Chinese reason, or null when the
     * throwable is something we should keep a stack trace for.
     *
     * <p>Deliberately narrow: the type must be a network failure <em>and</em> the message must
     * name one of the known disconnect conditions. A {@code FileNotFoundException} or an NPE
     * is an IOException-free or message-mismatched case and keeps its trace.</p>
     *
     * <p><b>This says nothing about who hung up first.</b> A reset is what you get when the
     * client cancels a download, but also when the client crashes, when Wi-Fi drops, or when
     * a NAT expires the flow — one {@code Connection reset} line is not evidence of a clean
     * exit. Deciding "normal exit vs. dropping connection and reconnecting" needs the
     * per-session lifecycle record that {@code FtpLogFtplet} writes on disconnect, not this
     * classifier. Keep the wording neutral here.</p>
     *
     * <p>Timeouts are intentionally <em>not</em> in this list: see
     * {@link #isServerSideTimeout}.</p>
     */
    private static String disconnectReason(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 5; depth++) {
            boolean networkFailure = current instanceof java.io.IOException
                    || current instanceof java.nio.channels.ClosedChannelException;
            if (networkFailure) {
                // ClosedChannelException carries no message; it only means the channel is gone.
                if (current instanceof java.nio.channels.ClosedChannelException) return "连接已关闭";
                String text = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.US);
                if (text.contains("connection reset")) return "连接被重置";
                if (text.contains("broken pipe")) return "管道已断开";
                if (text.contains("socket closed") || text.contains("socket is closed")
                        || text.contains("connection closed")) return "连接已关闭";
                if (text.contains("connection abort")) return "连接被中止";
            }
            current = current.getCause();
        }
        return null;
    }

    /**
     * True when the exception is <em>our</em> socket timeout firing, not the peer going away.
     *
     * <p>{@code SocketTimeoutException} is only thrown when a {@code SO_TIMEOUT} we set
     * expires. The FTP data socket gets {@code setSoTimeout(idleTime * 1000)} with
     * {@code idleTime = 60} (see {@code FtpService} / {@code IODataConnectionFactory}), so a
     * read timeout here means <b>the server waited 60 s for bytes and gave up</b> — a stalled
     * upload, a client that stopped sending, or a network that died mid-transfer. That is the
     * opposite of a clean client-side cancel and is exactly the "is it dropping?" signal, so
     * it keeps its stack trace and its ERROR level. Folding it into the disconnect branch
     * used to silence the one class of event that could actually prove a fault.</p>
     */
    private static boolean isServerSideTimeout(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current instanceof java.net.SocketTimeoutException) return true;
            String text = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.US);
            if (text.contains("read timed out") || text.contains("write timed out")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static String truncate(String value) {
        if (value == null) return "null";
        return value.length() <= MAX_MESSAGE_CHARS
                ? value
                : value.substring(0, MAX_MESSAGE_CHARS) + "…";
    }

    @Override
    public void close() {
        // No persistent resources to release.
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
