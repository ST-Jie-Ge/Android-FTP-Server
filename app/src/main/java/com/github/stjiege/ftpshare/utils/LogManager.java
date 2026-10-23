package com.github.stjiege.ftpshare.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.MyApplication;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded asynchronous logging. FTP callbacks only offer events; formatting,
 * snapshots and UI notifications run away from FTP worker threads.
 */
public final class LogManager {
    public static final String ACTION_UPDATED = "com.github.stjiege.ftpshare:logs_updated";
    /** A snapshot may temporarily grow by this many lines above the configured value. */
    private static final int TRIM_MAX_HEADROOM = 200;
    /** Start trimming before the snapshot reaches its temporary upper bound. */
    private static final int TRIM_TRIGGER_HEADROOM = TRIM_MAX_HEADROOM - 50;
    private static final String DISPLAY_TIMESTAMP_PATTERN = "MM-dd HH:mm:ss";
    private static final String FULL_TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final int DISPLAY_TIMESTAMP_PREFIX_LENGTH = 16;
    private static final int MAX_TRANSFER_SUMMARY_ACTORS = 64;
    private static final int LOGIN_EVENT_QUEUE_CAPACITY = 64;
    private static final int RUNTIME_EVENT_QUEUE_CAPACITY = 512;
    private static final int DRAIN_BATCH_SIZE = 128;
    private static final long UPDATE_DEBOUNCE_MS = 1_000L;
    private static final long TRANSFER_SUMMARY_INTERVAL_MS = 2_000L;
    /** Bound snapshots as well as queue events: logs must not become an OOM source under I/O errors. */
    private static final int MAX_ENTRY_CHARS = 4 * 1024;
    private static final int MAX_EVENT_LINES = 64;
    private static final int MAX_SAVED_LOG_FILES = 128;
    private static final long MAX_SAVED_LOG_BYTES = 32L * 1024L * 1024L;
    /** 日志与崩溃快照的最长保留时间，由定期维护任务负责老化。 */
    private static final long MAX_SAVED_LOG_AGE_MS = 30L * 24L * 60L * 60L * 1000L;

    private static final ArrayDeque<String> login = new ArrayDeque<>();
    private static final ArrayDeque<String> runtime = new ArrayDeque<>();
    private static final Object SNAPSHOT_LOCK = new Object();
    private static final Object STATE_LOCK = new Object();
    private static final Object WORKER_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static final BlockingQueue<LogEvent> LOGIN_EVENT_QUEUE =
            new ArrayBlockingQueue<>(LOGIN_EVENT_QUEUE_CAPACITY);
    private static final BlockingQueue<LogEvent> RUNTIME_EVENT_QUEUE =
            new ArrayBlockingQueue<>(RUNTIME_EVENT_QUEUE_CAPACITY);
    private static final AtomicBoolean DRAIN_POSTED = new AtomicBoolean(false);
    private static final AtomicInteger DROPPED_LOGIN_EVENTS = new AtomicInteger(0);
    private static final AtomicInteger DROPPED_RUNTIME_EVENTS = new AtomicInteger(0);
    /** Number of oldest in-memory physical lines discarded during this process. */
    private static long loginTrimmedLines;
    private static long runtimeTrimmedLines;

    private static volatile boolean started;
    private static volatile boolean loggingStateInitialized;
    private static volatile boolean loggingEnabled;
    private static volatile boolean loggingLocked;
    private static volatile boolean debugEnabled;
    private static volatile boolean infoEnabled;
    private static volatile int configuredMaxLines = Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT;
    private static HandlerThread workerThread;
    private static Handler workerHandler;
    private static boolean updateScheduled;
    // Worker-thread confined fields.
    private static boolean transferSummaryScheduled;
    private static final LinkedHashMap<String, TransferCounts> transferCounts = new LinkedHashMap<>();


    private static final Runnable UPDATE_TASK = new Runnable() {
        @Override public void run() {
            synchronized (SNAPSHOT_LOCK) { updateScheduled = false; }
            try {
                Context context = MyApplication.getGlobalBaseContext();
                Intent update = new Intent(ACTION_UPDATED);
                update.setPackage(context.getPackageName());
                context.sendBroadcast(update);
            } catch (RuntimeException ignored) {
                // Logging must never crash the app or recurse by logging its own broadcast failure.
            }
        }
    };

    private static final Runnable DRAIN_TASK = new Runnable() {
        @Override public void run() {
            try {
                drainBatch();
            } catch (Throwable error) {
                // 出事的可能就是 LogManager 自己（内存、格式化、快照裁剪……），往回记会
                // 递归，只能落到 logcat。绝不能让异常逃出去：HandlerThread 的 Looper 不接
                // 异常，Android 上未捕获异常直接杀死整个进程；即便线程侥幸存活，run() 末尾
                // 的 DRAIN_POSTED.set(false) 也执行不到，标志位永远卡在 true，此后 enqueue
                // 的 compareAndSet(false, true) 全部失败——日志从此永久静默，直到进程重启。
                android.util.Log.e("FTP-Log-Worker", "FTP log drain failed", error);
            } finally {
                // 标志位重置必须放在 finally：放正常尾路径就挡不住上面的异常路径。
                DRAIN_POSTED.set(false);
            }
            // 与重置前语义一致：批量处理完队列里可能又来了新事件（小窗口内 offer 成功但
            // 无人排空）。这里重查一次并抢占 CAS，保证任意时刻至多一个 DRAIN_TASK 在跑，
            // 且不会有事件滞留队列却无人消费。
            if ((!LOGIN_EVENT_QUEUE.isEmpty() || !RUNTIME_EVENT_QUEUE.isEmpty())
                    && DRAIN_POSTED.compareAndSet(false, true)) {
                workerHandler().post(this);
            }
        }

        private void drainBatch() {
            int handled = 0;
            LogEvent event;

            // Drain login events first: login failures must never be buried under high-rate
            // transfer/file-operation events from a busy FTP client.
            while (handled < DRAIN_BATCH_SIZE && (event = LOGIN_EVENT_QUEUE.poll()) != null) {
                handleEvent(event);
                handled++;
            }
            int droppedLogin = DROPPED_LOGIN_EVENTS.getAndSet(0);
            if (droppedLogin > 0 && loggingEnabled) {
                appendRuntime("WARNING 登录日志队列已满，已丢弃 " + droppedLogin + " 条登录记录");
            }

            while (handled < DRAIN_BATCH_SIZE && (event = RUNTIME_EVENT_QUEUE.poll()) != null) {
                handleEvent(event);
                handled++;
            }
            int droppedRuntime = DROPPED_RUNTIME_EVENTS.getAndSet(0);
            if (droppedRuntime > 0 && loggingEnabled) {
                appendRuntime("WARNING 运行日志队列已满，已丢弃 " + droppedRuntime + " 条普通消息");
            }
        }
    };

    private static final Runnable TRANSFER_SUMMARY_TASK = new Runnable() {
        @Override public void run() {
            transferSummaryScheduled = false;
            if (!loggingEnabled) {
                clearTransferCounts();
                return;
            }
            if (!transferCounts.isEmpty() && infoEnabled) {
                StringBuilder summary = new StringBuilder("INFO 操作汇总：");
                for (Map.Entry<String, TransferCounts> entry : transferCounts.entrySet()) {
                    TransferCounts counts = entry.getValue();
                    if (summary.length() > "INFO 操作汇总：".length()) summary.append("；");
                    summary.append(entry.getKey())
                            .append(counts.changes > 0 ? " 正在修改" : " 正在操作")
                            .append('(').append(counts.listings).append(')');
                }
                appendRuntime(summary.toString());
            }
            clearTransferCounts();
        }
    };

    private static final Runnable AUTO_SAVE_TASK = new Runnable() {
        @Override public void run() {
            Context context = MyApplication.getGlobalBaseContext();
            int hours = getAutoSaveHours(context);
            if (hours <= 0) return;
            // The main handler only schedules periodic work. Snapshotting and file I/O stay on
            // the log worker so an automatic save can never stall the UI or FTP lifecycle.
            final Context appContext = context.getApplicationContext();
            workerHandler().post(new Runnable() {
                @Override public void run() {
                    saveSnapshot(appContext, true);
                    saveSnapshot(appContext, false);
                }
            });
            MyApplication.handler.postDelayed(this, hours * 60L * 60L * 1000L);
        }
    };

    private LogManager() { }

    public interface SaveCallback {
        void onSaved(File file);
    }

    public static void start(Context context) {
        ensureWorker();
        if (started) return;
        started = true;
        reloadConfiguration(context);
        updateAutoSaveSchedule(context);
        if (loggingEnabled) {
            final Context appContext = context.getApplicationContext();
            workerHandler().post(new Runnable() {
                @Override public void run() {
                    restoreLatestRuntime(appContext);
                }
            });
        }
    }

    /** Reload settings outside the FTP callback path. */
    public static void reloadConfiguration(Context context) {
        synchronized (STATE_LOCK) {
            int hours = getAutoSaveHours(context);
            loggingLocked = hours > 0;
            loggingEnabled = loggingLocked || CommonUtils.getSettingSharedPreferences(context)
                    .getBoolean(Constants.PreferenceConsts.LOG_ENABLED, Constants.PreferenceConsts.LOG_ENABLED_DEFAULT);
            String level = CommonUtils.getSettingSharedPreferences(context).getString(
                    Constants.PreferenceConsts.LOG_LEVEL, Constants.PreferenceConsts.LOG_LEVEL_DEFAULT);
            debugEnabled = Constants.PreferenceConsts.LOG_LEVEL_DEBUG.equals(level);
            infoEnabled = debugEnabled || Constants.PreferenceConsts.LOG_LEVEL_INFO.equals(level);
            android.content.SharedPreferences settings = CommonUtils.getSettingSharedPreferences(context);
            int max = settings.getInt(Constants.PreferenceConsts.LOG_MAX_LINES,
                    Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT);
            // The former 2000-line choice is now 1500; migrate persisted selections once.
            if (max == 2000) {
                max = 1500;
                settings.edit().putInt(Constants.PreferenceConsts.LOG_MAX_LINES, max).apply();
            }
            configuredMaxLines = max == 1000 || max == 1500
                    ? max : Constants.PreferenceConsts.LOG_MAX_LINES_DEFAULT;
            loggingStateInitialized = true;
        }
        trimToConfiguredLimit();
    }

    public static void updateAutoSaveSchedule(Context context) {
        MyApplication.handler.removeCallbacks(AUTO_SAVE_TASK);
        int hours = getAutoSaveHours(context);
        if (hours > 0) {
            CommonUtils.getSettingSharedPreferences(context).edit()
                    .putBoolean(Constants.PreferenceConsts.LOG_ENABLED, true).apply();
            MyApplication.handler.postDelayed(AUTO_SAVE_TASK, hours * 60L * 60L * 1000L);
        }
        reloadConfiguration(context);
        scheduleUiUpdate();
    }

    private static int getAutoSaveHours(Context context) {
        int hours = CommonUtils.getSettingSharedPreferences(context).getInt(
                Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS,
                Constants.PreferenceConsts.LOG_AUTO_SAVE_HOURS_DEFAULT);
        return hours == 3 || hours == 6 || hours == 12 ? hours : 0;
    }

    public static boolean isLoggingLocked(Context context) {
        ensureLoggingState(context);
        return loggingLocked;
    }

    public static boolean isLoggingEnabled(Context context) {
        ensureLoggingState(context);
        return loggingEnabled;
    }

    public static boolean setLoggingEnabled(Context context, boolean enabled) {
        ensureLoggingState(context);
        if (loggingLocked) return false;
        CommonUtils.getSettingSharedPreferences(context).edit()
                .putBoolean(Constants.PreferenceConsts.LOG_ENABLED, enabled).apply();
        reloadConfiguration(context);
        if (!enabled) {
            LOGIN_EVENT_QUEUE.clear();
            RUNTIME_EVENT_QUEUE.clear();
            DROPPED_LOGIN_EVENTS.set(0);
            DROPPED_RUNTIME_EVENTS.set(0);
            synchronized (SNAPSHOT_LOCK) {
                login.clear();
                runtime.clear();
                loginTrimmedLines = 0L;
                runtimeTrimmedLines = 0L;
            }
            Handler handler = workerHandler();
            handler.removeCallbacks(TRANSFER_SUMMARY_TASK);
            handler.post(new Runnable() {
                @Override public void run() {
                    transferSummaryScheduled = false;
                    clearTransferCounts();
                }
            });
        }
        scheduleUiUpdate();
        return true;
    }

    /** FTP callback fast gate: a volatile read only. */
    public static boolean isLoggingEnabled() { return loggingEnabled; }
    public static boolean isDebugEnabled() { return loggingEnabled && debugEnabled; }

    private static void ensureLoggingState(Context context) {
        if (!loggingStateInitialized) reloadConfiguration(context);
    }

    public static void addLogin(String message) { enqueue(new LogEvent(EventType.LOGIN, message, null)); }
    public static void debug(String message) {
        if (isDebugEnabled()) enqueue(new LogEvent(EventType.DEBUG, message, null));
    }
    public static void info(String message) {
        if (loggingEnabled && infoEnabled) enqueue(new LogEvent(EventType.INFO, message, null));
    }
    public static void warning(String message) {
        if (loggingEnabled) enqueue(new LogEvent(EventType.WARNING, message, null));
    }

    /** Records the authenticated user and client address with each aggregated FTP operation. */
    public static void recordFtpFileOperation(String command, String user, String address) {
        if (!loggingEnabled) return;
        String actor = (user == null || user.trim().isEmpty() ? "未知用户" : user.trim())
                + " @ " + (address == null || address.trim().isEmpty() ? "未知地址" : address.trim());
        EventType type = "RETR".equalsIgnoreCase(command) ? EventType.DOWNLOAD
                : ("STOR".equalsIgnoreCase(command) || "APPE".equalsIgnoreCase(command)) ? EventType.UPLOAD
                : ("LIST".equalsIgnoreCase(command) || "NLST".equalsIgnoreCase(command)) ? EventType.LIST
                : EventType.CHANGE;
        enqueue(new LogEvent(type, actor, null));
    }

    /** Stack rendering is deliberately deferred to the log worker. */
    public static void error(String message, Throwable error) {
        if (loggingEnabled) enqueue(new LogEvent(EventType.ERROR, message, error));
    }

    private static void enqueue(LogEvent event) {
        if (!loggingEnabled) return;
        boolean isLogin = event != null && event.type == EventType.LOGIN;
        BlockingQueue<LogEvent> queue = isLogin ? LOGIN_EVENT_QUEUE : RUNTIME_EVENT_QUEUE;
        AtomicInteger dropped = isLogin ? DROPPED_LOGIN_EVENTS : DROPPED_RUNTIME_EVENTS;
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            return;
        }
        if (DRAIN_POSTED.compareAndSet(false, true)) workerHandler().post(DRAIN_TASK);
    }

    private static void handleEvent(LogEvent event) {
        if (!loggingEnabled) return;
        switch (event.type) {
            case LOGIN: appendLogin(event.message); break;
            case DEBUG: if (debugEnabled) appendRuntime("DEBUG " + event.message); break;
            case INFO: if (infoEnabled) appendRuntime("INFO " + event.message); break;
            case WARNING: appendRuntime("WARNING " + event.message); break;
            case ERROR: appendRuntime("ERROR " + event.message + stackTrace(event.error)); break;
            case DOWNLOAD: transferCounts(event.message).downloads++; scheduleTransferSummary(); break;
            case UPLOAD: transferCounts(event.message).uploads++; scheduleTransferSummary(); break;
            case CHANGE: transferCounts(event.message).changes++; scheduleTransferSummary(); break;
            case LIST: transferCounts(event.message).listings++; scheduleTransferSummary(); break;
        }
    }

    private static String stackTrace(Throwable error) {
        if (error == null) return "";
        StringWriter stack = new StringWriter();
        error.printStackTrace(new PrintWriter(stack));
        return "\n" + limitText(stack.toString());
    }

    private static void scheduleTransferSummary() {
        if (transferSummaryScheduled) return;
        transferSummaryScheduled = true;
        workerHandler().postDelayed(TRANSFER_SUMMARY_TASK, TRANSFER_SUMMARY_INTERVAL_MS);
    }

    private static void clearTransferCounts() {
        transferCounts.clear();
    }

    private static TransferCounts transferCounts(String actor) {
        TransferCounts counts = transferCounts.get(actor);
        if (counts != null) return counts;
        if (transferCounts.size() >= MAX_TRANSFER_SUMMARY_ACTORS) actor = "其他用户 @ 多个地址";
        counts = transferCounts.get(actor);
        if (counts == null) {
            counts = new TransferCounts();
            transferCounts.put(actor, counts);
        }
        return counts;
    }

    private static void appendLogin(String message) { append(login, message); }
    private static void appendRuntime(String message) { append(runtime, message); }

    /** Executed by the dedicated worker, never by the FTP thread. */
    private static void append(ArrayDeque<String> target, String message) {
        String normalized = limitText(message == null ? "" : message)
                .replace("\r\n", "\n").replace('\r', '\n');
        String[] physicalLines = normalized.split("\n", -1);
        int retainedLines = Math.min(physicalLines.length, MAX_EVENT_LINES);
        boolean truncatedLines = physicalLines.length > retainedLines;
        int incomingLines = retainedLines + (truncatedLines ? 1 : 0);
        String timestamp = new SimpleDateFormat(DISPLAY_TIMESTAMP_PATTERN, Locale.US).format(new Date());
        synchronized (SNAPSHOT_LOCK) {
            recordTrimmedLines(target, trimForIncomingLines(target, incomingLines));
            for (int index = 0; index < retainedLines; index++) {
                target.addLast(index == 0
                        ? timestamp + "  " + physicalLines[index]
                        : "                    " + physicalLines[index]);
            }
            if (truncatedLines) target.addLast("                    … 其余 "
                    + (physicalLines.length - retainedLines) + " 行已截断");
        }
        scheduleUiUpdate();
    }

    private static String limitText(String value) {
        if (value.length() <= MAX_ENTRY_CHARS) return value;
        return value.substring(0, MAX_ENTRY_CHARS) + "\n… 日志内容已截断";
    }

    private static void scheduleUiUpdate() {
        synchronized (SNAPSHOT_LOCK) {
            if (updateScheduled) return;
            updateScheduled = true;
        }
        MyApplication.handler.postDelayed(UPDATE_TASK, UPDATE_DEBOUNCE_MS);
    }

    public static String getLoginText() { return text(login); }
    public static String getRuntimeText() { return text(runtime); }

    /** Small in-memory tail for the clock. Never formats the entire log or reads disk. */
    public static String getClockLogText() {
        if (!loggingEnabled) return "日志已关闭";
        ArrayDeque<String> tail = new ArrayDeque<>();
        synchronized (SNAPSHOT_LOCK) {
            java.util.Iterator<String> lines = runtime.descendingIterator();
            while (lines.hasNext() && tail.size() < 4) {
                String line = lines.next();
                // The in-memory timestamp is month-day plus time, followed by two spaces.
                if (!line.trim().isEmpty()) {
                    tail.addFirst(line.substring(Math.min(line.length(), DISPLAY_TIMESTAMP_PREFIX_LENGTH), Math.min(line.length(), 191)));
                }
            }
        }
        return join(new ArrayList<>(tail));
    }


    private static String text(ArrayDeque<String> source) {
        synchronized (SNAPSHOT_LOCK) { return join(new ArrayList<>(source)); }
    }

    private static String join(List<String> lines) {
        StringBuilder result = new StringBuilder();
        for (String line : lines) result.append(line).append('\n');
        return result.toString();
    }

    public static void trimToConfiguredLimit() {
        synchronized (SNAPSHOT_LOCK) {
            recordTrimmedLines(login, trimToConfiguredLimit(login));
            recordTrimmedLines(runtime, trimToConfiguredLimit(runtime));
        }
    }

    /**
     * Start trimming shortly before the temporary upper bound, then return exactly to the
     * configured retained count. For example, the 500-line option starts at about 650 lines
     * and can never grow past 700 lines between trim checks.
     */
    private static int trimForIncomingLines(ArrayDeque<String> target, int incomingLines) {
        int maximum = Math.max(1, configuredMaxLines);
        if (incomingLines >= maximum) {
            int removed = target.size();
            target.clear();
            return removed;
        }
        int sizeAfterAppend = target.size() + incomingLines;
        if (sizeAfterAppend <= maximum + TRIM_TRIGGER_HEADROOM) return 0;
        return removeOldest(target, sizeAfterAppend - maximum);
    }

    private static int trimToConfiguredLimit(ArrayDeque<String> target) {
        int overflow = target.size() - Math.max(1, configuredMaxLines);
        return overflow > 0 ? removeOldest(target, overflow) : 0;
    }

    private static int removeOldest(ArrayDeque<String> target, int count) {
        int removed = 0;
        while (removed < count && !target.isEmpty()) {
            target.removeFirst();
            removed++;
        }
        return removed;
    }

    private static void recordTrimmedLines(ArrayDeque<String> target, int removed) {
        if (removed <= 0) return;
        if (target == login) loginTrimmedLines += removed;
        else if (target == runtime) runtimeTrimmedLines += removed;
    }

    /** Schedules snapshotting and file I/O on the dedicated log worker. */
    public static void saveAsync(Context context, final boolean loginOnly, final SaveCallback callback) {
        final Context appContext = context.getApplicationContext();
        workerHandler().post(new Runnable() {
            @Override public void run() {
                final File saved = saveSnapshot(appContext, loginOnly);
                if (callback != null) {
                    MyApplication.handler.post(new Runnable() {
                        @Override public void run() { callback.onSaved(saved); }
                    });
                }
            }
        });
    }

    /** Saves both panes together so a manual export is one coherent diagnostic snapshot. */
    public static void saveAllAsync(Context context, final SaveCallback callback) {
        final Context appContext = context.getApplicationContext();
        workerHandler().post(new Runnable() {
            @Override public void run() {
                final File saved = saveAllSnapshot(appContext);
                if (callback != null) {
                    MyApplication.handler.post(new Runnable() {
                        @Override public void run() { callback.onSaved(saved); }
                    });
                }
            }
        });
    }

    /** Executed only by the log worker, except fatal-report handling during a process crash. */
    private static File saveSnapshot(Context context, boolean loginOnly) {
        File directory = getWritableLogDirectory(context);
        if (directory == null) return null;
        synchronized (FILE_LOCK) {
            File file = newLogFile(directory, loginOnly ? "login" : "runtime");
            if (!writeTextFile(file, snapshotTextForSave(loginOnly))) {
                warning("保存日志失败");
                return null;
            }
            pruneSavedLogs(directory);
            return file;
        }
    }

    private static File saveAllSnapshot(Context context) {
        File directory = getWritableLogDirectory(context);
        if (directory == null) return null;
        synchronized (FILE_LOCK) {
            File file = newLogFile(directory, "logs");
            if (!writeTextFile(file, combinedSnapshotTextForSave())) {
                warning("保存日志失败");
                return null;
            }
            pruneSavedLogs(directory);
            return file;
        }
    }

    private static String snapshotTextForSave(boolean loginOnly) {
        synchronized (SNAPSHOT_LOCK) {
            return snapshotTextForSave(loginOnly ? login : runtime,
                    loginOnly ? loginTrimmedLines : runtimeTrimmedLines);
        }
    }

    private static String combinedSnapshotTextForSave() {
        synchronized (SNAPSHOT_LOCK) {
            return "导出时间：" + fullTimestamp() + "\n登录日志\n"
                    + snapshotBodyForSave(login, loginTrimmedLines)
                    + "\n运行日志\n" + snapshotBodyForSave(runtime, runtimeTrimmedLines);
        }
    }

    private static String snapshotTextForSave(ArrayDeque<String> source, long trimmedLines) {
        return "导出时间：" + fullTimestamp() + "\n" + snapshotBodyForSave(source, trimmedLines);
    }

    private static String snapshotBodyForSave(ArrayDeque<String> source, long trimmedLines) {
        String prefix = trimmedLines <= 0 ? "" : "提示：内存日志已裁剪，较早的 "
                + trimmedLines + " 行未包含在此文件中。\n";
        return prefix + join(new ArrayList<>(source));
    }

    private static String fullTimestamp() {
        return new SimpleDateFormat(FULL_TIMESTAMP_PATTERN, Locale.US).format(new Date());
    }

    /** Fatal reports are written even while optional runtime logging is paused. */
    public static void saveFatalReport(Context context, String message, Throwable error) {
        try {
            File directory = getWritableLogDirectory(context);
            if (directory == null) return;
            String contents = fullTimestamp()
                    + "  " + message + "\n" + stackTrace(error) + "\n最近运行日志：\n" + getRuntimeText();
            synchronized (FILE_LOCK) {
                if (writeTextFile(newLogFile(directory, "crash"), contents)) pruneSavedLogs(directory);
            }
        } catch (Exception ignored) {
            // A fatal-report write failure must not mask the original fatal error.
        }
    }

    private static File newLogFile(File directory, String prefix) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        File file = new File(directory, prefix + "-" + stamp + ".txt");
        int suffix = 1;
        while (file.exists()) file = new File(directory, prefix + "-" + stamp + "-" + suffix++ + ".txt");
        return file;
    }

    private static boolean writeTextFile(File file, String contents) {
        File temporary = new File(file.getParentFile(), "." + file.getName() + ".tmp");
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temporary), "UTF-8"))) {
            writer.write(contents);
            writer.flush();
            if (file.exists() && !file.delete()) return false;
            return temporary.renameTo(file);
        } catch (Exception ignored) {
            // This is called from the log worker; reporting its own I/O failure would recurse.
            return false;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    /**
     * 定期维护入口：把应用私有的运行/登录/崩溃日志剪到上限内，并老化掉过久的旧文件。
     *
     * <p>数量与总量的剪枝原本只在「保存日志时」顺带做一次，长期不手动导出的话，一批很老的
     * 文件会一直占着目录；这里补上时间维度。返回删除的文件数。</p>
     */
    public static int pruneSavedLogs(Context context) {
        File directory = getWritableLogDirectory(context.getApplicationContext());
        if (directory == null) return 0;
        synchronized (FILE_LOCK) {
            return pruneSavedLogs(directory);
        }
    }

    /** Keeps app-private automatic and crash logs bounded during long-running use. */
    private static int pruneSavedLogs(File directory) {
        int removed = ageOutOldFiles(directory);
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return removed;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        long totalBytes = 0L;
        for (File file : files) totalBytes += file.length();
        int remaining = files.length;
        for (File file : files) {
            if (remaining <= MAX_SAVED_LOG_FILES && totalBytes <= MAX_SAVED_LOG_BYTES) break;
            long length = file.length();
            if (file.delete()) {
                remaining--;
                totalBytes -= length;
                removed++;
            }
        }
        return removed;
    }

    /**
     * 删掉超过 {@link #MAX_SAVED_LOG_AGE_MS} 的日志。诊断日志的价值随时间衰减很快，
     * 留一个月足够覆盖"用户发现问题时回看"的场景，再久就只是在占空间。
     */
    private static int ageOutOldFiles(File directory) {
        File[] files = directory.listFiles(File::isFile);
        if (files == null) return 0;
        long cutoff = System.currentTimeMillis() - MAX_SAVED_LOG_AGE_MS;
        int removed = 0;
        for (File file : files) {
            if (file.lastModified() < cutoff && file.delete()) removed++;
        }
        return removed;
    }

    private static void restoreLatestRuntime(Context context) {
        try {
            File dir = getLogDirectory(context);
            File[] files = dir.listFiles((d, name) -> name.startsWith("runtime-") && name.endsWith(".txt"));
            if (files == null || files.length == 0) return;
            File latest = files[0];
            for (File file : files) if (file.lastModified() > latest.lastModified()) latest = file;
            synchronized (SNAPSHOT_LOCK) {
                try (BufferedReader reader = new BufferedReader(new FileReader(latest))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        trimForIncomingLines(runtime, 1);
                        runtime.addLast(limitText(line));
                    }
                }
            }
        } catch (Exception ignored) {
            // Previous-runtime restoration is best effort; corrupt or deleted logs are ignored.
        }
    }

    public static File getLogDirectory(Context context) { return new File(context.getFilesDir(), "ftplogs"); }

    public static File getWritableLogDirectory(Context context) {
        File directory = getLogDirectory(context);
        try {
            if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory()) return null;
            return directory.canWrite() ? directory : null;
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static void ensureWorker() { workerHandler(); }
    private static Handler workerHandler() {
        synchronized (WORKER_LOCK) {
            if (workerHandler == null) {
                workerThread = new HandlerThread("FTP-Log-Worker");
                workerThread.start();
                workerHandler = new Handler(workerThread.getLooper());
            }
            return workerHandler;
        }
    }

    private enum EventType { LOGIN, DEBUG, INFO, WARNING, ERROR, DOWNLOAD, UPLOAD, CHANGE, LIST }
    private static final class TransferCounts {
        int downloads;
        int uploads;
        int changes;
        int listings;
    }
    private static final class LogEvent {
        final EventType type;
        final String message;
        final Throwable error;
        LogEvent(EventType type, String message, Throwable error) {
            this.type = type;
            this.message = message;
            this.error = error;
        }
    }
}
