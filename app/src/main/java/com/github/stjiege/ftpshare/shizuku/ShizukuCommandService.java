package com.github.stjiege.ftpshare.shizuku;

import android.os.Build;

import com.github.stjiege.ftpshare.BuildConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Restricted Shizuku user service. Its Binder API exposes only this application's Doze setting
 * and watchdog; no caller can supply a shell command, package name, or component name.
 */
public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String EXPECTED_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String EXPECTED_SERVICE = EXPECTED_PACKAGE + ".services.FtpService";
    private static final int MAX_RECOVERIES = 3;
    private static final long COMMAND_TIMEOUT_SECONDS = 15L;
    private static final int MAX_COMMAND_OUTPUT_CHARS = 128 * 1024;
    private static final AtomicInteger nextReaderId = new AtomicInteger(1);
    private static final ExecutorService OUTPUT_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "ftpserverg2-shell-reader-" + nextReaderId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    /** First probe delay: the guard is armed only after the FTP listener is already up. */
    private static final long FIRST_PROBE_DELAY_MS = 8_000L;
    private static final long PROBE_INTERVAL_MS = 45_000L;
    private static final long RECOVERY_CONFIRM_DELAY_MS = 15_000L;
    private static final long UNKNOWN_RETRY_DELAY_MS = 30_000L;
    private static final long POST_RECOVERY_DELAY_MS = 25_000L;
    /** ~30 min of never seeing a live FTP: stop idling so a stale daemon cannot linger forever. */
    private static final int MAX_IDLE_PROBES = 40;

    private enum FtpState { RUNNING, NOT_RUNNING, UNKNOWN }

    private volatile boolean watching;
    /** Recovery is allowed only after FTP was observed running, so a guard armed right before a
     * deliberate stop can never resurrect a service the user just ended. */
    private volatile boolean everSeenRunning;
    private volatile String watchStatus = "看门狗未运行";
    private Thread watchThread;

    @Override public boolean setDozeWhitelist(boolean enabled) {
        return runCommand("cmd", "deviceidle", "whitelist", (enabled ? "+" : "-") + EXPECTED_PACKAGE).success;
    }

    @Override public boolean isDozeWhitelisted() {
        CommandResult result = runCommand("cmd", "deviceidle", "whitelist");
        return result.success && result.output.contains(EXPECTED_PACKAGE);
    }

    @Override public synchronized void startWatch() {
        if (watching) {
            watchStatus = "看门狗正在监测 FTP 服务";
            return;
        }
        watching = true;
        everSeenRunning = false;
        watchStatus = "看门狗正在监测 FTP 服务";
        watchThread = new Thread(this::monitor, "ftpserverg2-shizuku-watchdog");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    @Override public synchronized void stopWatch() {
        watching = false;
        watchStatus = "看门狗已由用户停止";
        // The app-side controller unbinds this dedicated user service with remove=true. Do not
        // terminate the Binder process manually: it can race the reply and cause a disconnect.
        if (watchThread != null) watchThread.interrupt();
    }

    @Override public boolean isWatching() { return watching; }
    @Override public String getWatchStatus() { return watchStatus; }

    private void monitor() {
        int recoveryCount = 0;
        int idleProbes = 0;
        try {
            // Let foreground startup settle before the first probe, preventing a false recovery.
            Thread.sleep(FIRST_PROBE_DELAY_MS);
            while (watching) {
                FtpState state = probeFtp();
                if (state == FtpState.UNKNOWN) {
                    // A failed probe means "unknown", never "stopped": an unreadable dumpsys must
                    // not be mistaken for a dead service and burn a recovery attempt.
                    watchStatus = "无法确认 FTP 运行状态，稍后重试";
                    Thread.sleep(UNKNOWN_RETRY_DELAY_MS);
                    continue;
                }
                if (state == FtpState.RUNNING) {
                    everSeenRunning = true;
                    watchStatus = "看门狗正在监测 FTP 服务";
                    Thread.sleep(PROBE_INTERVAL_MS);
                    continue;
                }
                if (!everSeenRunning) {
                    // Armed but never observed a live FTP: either the user stopped it right away or
                    // the guard was attached to a startup that never completed. Do not start it.
                    idleProbes++;
                    if (idleProbes >= MAX_IDLE_PROBES) {
                        watchStatus = "长时间未检测到 FTP 运行，看门狗已退出";
                        watching = false;
                        return;
                    }
                    watchStatus = "未检测到运行中的 FTP，暂不恢复";
                    Thread.sleep(PROBE_INTERVAL_MS);
                    continue;
                }
                // A force-stop is an explicit user/system choice. Do not countermand it by
                // starting the application again.
                if (isForceStopped()) {
                    watchStatus = "检测到强制停止，未恢复 FTP";
                    watching = false;
                    return;
                }
                Thread.sleep(RECOVERY_CONFIRM_DELAY_MS);
                if (!watching) return;
                FtpState confirmed = probeFtp();
                if (confirmed == FtpState.RUNNING) {
                    watchStatus = "看门狗正在监测 FTP 服务";
                    continue;
                }
                if (confirmed == FtpState.UNKNOWN) continue;
                if (isForceStopped()) {
                    watchStatus = "检测到强制停止，未恢复 FTP";
                    watching = false;
                    return;
                }
                if (recoveryCount >= MAX_RECOVERIES) {
                    watchStatus = "已达到 3 次恢复上限";
                    watching = false;
                    return;
                }
                recoveryCount++;
                watchStatus = "正在恢复 FTP 服务（" + recoveryCount + "/" + MAX_RECOVERIES + "）";
                runCommand("am", Build.VERSION.SDK_INT >= 26 ? "start-foreground-service" : "startservice",
                        "-n", EXPECTED_PACKAGE + "/" + EXPECTED_SERVICE);
                Thread.sleep(POST_RECOVERY_DELAY_MS);
                FtpState after = probeFtp();
                watchStatus = after == FtpState.RUNNING
                        ? "看门狗正在监测 FTP 服务"
                        : (after == FtpState.UNKNOWN
                                ? "恢复结果未知（" + recoveryCount + "/" + MAX_RECOVERIES + "）"
                                : "恢复尝试失败（" + recoveryCount + "/" + MAX_RECOVERIES + "）");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // A crashed monitor must not keep reporting "watching": the caller would then refuse
            // to start a new one and the guard would stay dead forever.
            watching = false;
            watchStatus = "看门狗异常：" + error.getClass().getSimpleName();
        } finally {
            if (!watching && !watchStatus.startsWith("检测到") && !watchStatus.startsWith("看门狗已")) {
                watchStatus = "看门狗未运行";
            }
        }
    }

    /** Three-state probe: an unreadable or timed-out dumpsys is never read as "stopped". */
    private FtpState probeFtp() {
        CommandResult result = runCommand("dumpsys", "activity", "services", EXPECTED_PACKAGE);
        if (!result.success) return FtpState.UNKNOWN;
        return result.output.contains(EXPECTED_SERVICE) ? FtpState.RUNNING : FtpState.NOT_RUNNING;
    }

    private boolean isForceStopped() {
        return runCommand("dumpsys", "package", EXPECTED_PACKAGE).output.contains("stopped=true");
    }

    /** Runs one fixed argv command with a bounded wait and continuously drains its output. */
    private synchronized CommandResult runCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process processToRead = process;
            Future<String> output = OUTPUT_EXECUTOR.submit(new Callable<String>() {
                @Override public String call() throws Exception {
                    StringBuilder collected = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(processToRead.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (collected.length() < MAX_COMMAND_OUTPUT_CHARS) {
                                int remaining = MAX_COMMAND_OUTPUT_CHARS - collected.length();
                                if (line.length() <= remaining) collected.append(line);
                                else collected.append(line, 0, remaining);
                                collected.append('\n');
                            }
                        }
                    }
                    return collected.toString();
                }
            });
            if (!waitForProcess(process, COMMAND_TIMEOUT_SECONDS * 1_000L)) {
                process.destroy();
                // Process.destroyForcibly() requires API 26. Keep the user service
                // compatible with Android 6/7 and do not block this binder call forever.
                waitForProcess(process, 2_000L);
                return CommandResult.failure("command timeout");
            }
            String text = output.get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new CommandResult(process.exitValue() == 0, text);
        } catch (Exception error) {
            if (process != null) process.destroy();
            return CommandResult.failure(error.getClass().getSimpleName());
        }
    }

    /** Process.waitFor(timeout) is unavailable on the Android 6 baseline. */
    private static boolean waitForProcess(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try {
                process.exitValue();
                return true;
            } catch (IllegalThreadStateException ignored) {
                if (System.currentTimeMillis() >= deadline) return false;
                Thread.sleep(50L);
            }
        }
    }

    private static final class CommandResult {
        final boolean success;
        final String output;

        CommandResult(boolean success, String output) {
            this.success = success;
            this.output = output;
        }

        static CommandResult failure(String reason) { return new CommandResult(false, reason); }
    }
}
