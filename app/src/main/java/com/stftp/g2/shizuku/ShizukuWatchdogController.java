package com.stftp.g2.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.stftp.g2.Constants;
import com.stftp.g2.services.FtpService;
import com.stftp.g2.utils.CommonUtils;
import com.stftp.g2.utils.LogManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Coordinates the dedicated daemon user service that can recover an unexpectedly killed FTP service. */
public final class ShizukuWatchdogController {
    private static final String TAG = "ftpserverg2-watchdog";
    // Must track a UserService implementation change so Shizuku replaces a cached process.
    private static final int USER_SERVICE_VERSION = 23;
    /** A bind with no callback at all must not latch the controller into "binding forever". */
    private static final long BIND_TIMEOUT_MS = 8_000L;
    private static final int MAX_BIND_ATTEMPTS = 3;
    /** Shizuku may still be starting right after boot; a couple of late attempts are cheap. */
    private static final int MAX_START_ATTEMPTS = 3;
    private static final long START_RETRY_DELAY_MS = 20_000L;

    private static final ExecutorService CONTROL_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ftpserverg2-watchdog-control");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static volatile IShizukuCommandService service;
    private static volatile Context appContext;
    private static volatile boolean bound;
    private static volatile boolean binding;
    private static volatile boolean stopWhenConnected;
    /** Whether the guard is supposed to be running; drives every retry decision. */
    private static volatile boolean watchRequested;
    private static volatile int bindAttempts;
    private static volatile int startAttempts;
    private static volatile String status = "看门狗未运行";

    private ShizukuWatchdogController() { }

    private static Shizuku.UserServiceArgs args() {
        return new Shizuku.UserServiceArgs(new ComponentName(appContext, ShizukuCommandService.class))
                .tag(TAG)
                // Required by the Shizuku 12.1.0 API when creating the remote user process.
                .processNameSuffix("ftpserverg2-watchdog")
                .version(USER_SERVICE_VERSION)
                .daemon(true);
    }

    private static final Runnable BIND_TIMEOUT_TASK = new Runnable() {
        @Override public void run() {
            if (bound || !binding) return;
            binding = false;
            status = "连接 Shizuku 看门狗超时";
            LogManager.warning("Shizuku watchdog connection timed out");
            if (watchRequested && bindAttempts < MAX_BIND_ATTEMPTS) {
                bindAsync();
            } else if (watchRequested) {
                scheduleStartRetry();
            }
        }
    };

    private static final Runnable START_RETRY_TASK = new Runnable() {
        @Override public void run() {
            if (!watchRequested) return;
            if (appContext == null || !isEnabled(appContext) || !FtpService.isFTPServiceRunning()
                    || FtpService.isAttackLockdown(appContext)) {
                // FTP ended in the meantime: there is nothing left to guard.
                watchRequested = false;
                return;
            }
            status = "正在重试启动 Shizuku 看门狗";
            if (!granted()) {
                scheduleStartRetry();
                return;
            }
            bindAttempts = 0;
            bindAsync();
        }
    };

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            MAIN_HANDLER.removeCallbacks(BIND_TIMEOUT_TASK);
            final IShizukuCommandService connectedService = IShizukuCommandService.Stub.asInterface(binder);
            if (connectedService == null) {
                invalidate("Shizuku watchdog returned no service");
                return;
            }
            synchronized (ShizukuWatchdogController.class) {
                binding = false;
                bound = true;
                service = connectedService;
                bindAttempts = 0;
            }
            if (stopWhenConnected) {
                stopWatchAsync(connectedService);
                return;
            }
            startWatchAsync();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            invalidate("Shizuku watchdog disconnected");
        }
    };

    public static synchronized boolean isEnabled(Context context) {
        return CommonUtils.getSettingSharedPreferences(context).getBoolean(
                Constants.PreferenceConsts.SHIZUKU_WATCHDOG,
                Constants.PreferenceConsts.SHIZUKU_WATCHDOG_DEFAULT);
    }

    /** Starts the guard only after a successful FTP start and only when Shizuku was authorized. */
    public static synchronized void startIfEnabled(Context context) {
        appContext = context.getApplicationContext();
        if (!isEnabled(appContext) || FtpService.isAttackLockdown(appContext)
                || !FtpService.isFTPServiceRunning()) {
            watchRequested = false;
            status = "看门狗未启用";
            return;
        }
        stopWhenConnected = false;
        watchRequested = true;
        startAttempts = 1;
        startWatchInternal();
    }

    private static void startWatchInternal() {
        try {
            if (!granted()) {
                status = "Shizuku 未授权，看门狗未启动";
                scheduleStartRetry();
                return;
            }
            if (service != null) {
                startWatchAsync();
            } else if (!binding) {
                bindAttempts = 0;
                bindAsync();
            }
        } catch (Exception error) {
            invalidate("Shizuku watchdog unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void scheduleStartRetry() {
        if (!watchRequested || startAttempts >= MAX_START_ATTEMPTS) return;
        startAttempts++;
        MAIN_HANDLER.removeCallbacks(START_RETRY_TASK);
        MAIN_HANDLER.postDelayed(START_RETRY_TASK, START_RETRY_DELAY_MS);
    }

    private static void bindAsync() {
        if (bound) return;
        bindAttempts++;
        status = "正在连接 Shizuku 看门狗";
        MAIN_HANDLER.post(new Runnable() {
            @Override public void run() {
                if (bound) return;
                binding = true;
                MAIN_HANDLER.removeCallbacks(BIND_TIMEOUT_TASK);
                MAIN_HANDLER.postDelayed(BIND_TIMEOUT_TASK, BIND_TIMEOUT_MS);
                try {
                    Shizuku.bindUserService(args(), CONNECTION);
                } catch (Exception error) {
                    binding = false;
                    MAIN_HANDLER.removeCallbacks(BIND_TIMEOUT_TASK);
                    invalidate("Shizuku watchdog bind failed: " + error.getClass().getSimpleName());
                }
            }
        });
    }

    private static void startWatchAsync() {
        final IShizukuCommandService target = service;
        if (target == null) {
            startWatchInternal();
            return;
        }
        CONTROL_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                    // Serialize with stop(): queued work must not rearm a stopped guard.
                    if (!watchRequested || stopWhenConnected || target != service
                            || appContext == null || FtpService.isAttackLockdown(appContext)
                            || !FtpService.isFTPServiceRunning()) return;
                    try {
                        target.startWatch();
                        status = target.getWatchStatus();
                    } catch (Throwable error) {
                        invalidate("Shizuku watchdog call failed: " + error.getClass().getSimpleName());
                    }

            }
        });
    }

    /**
     * Forgets a dead or unreachable binding so the next start can create a fresh daemon instead of
     * calling a stale Binder forever. Retries only while the guard is actually wanted.
     */
    private static void invalidate(String message) {
        LogManager.warning(message);
        synchronized (ShizukuWatchdogController.class) {
            service = null;
            bound = false;
            binding = false;
        }
        status = "Shizuku 看门狗未连接";
        if (watchRequested && bindAttempts < MAX_BIND_ATTEMPTS && granted()) {
            bindAsync();
            return;
        }
        if (watchRequested) scheduleStartRetry();
    }

    private static boolean granted() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Exception error) {
            return false;
        }
    }

    /** Stops the guard before every expected stop, including user, timer, and network policy stops. */
    public static synchronized void stop(Context context) {
        appContext = context.getApplicationContext();
        watchRequested = false;
        MAIN_HANDLER.removeCallbacks(START_RETRY_TASK);
        // When no guard was armed for this app process, do not create a Shizuku user service just
        // because an ordinary FTP startup check failed.
        if (!isEnabled(appContext) && service == null && !bound && !binding) {
            status = "看门狗未启用";
            return;
        }
        stopWhenConnected = true;
        try {
            if (!granted()) {
                status = "Shizuku 未授权，无法停止看门狗";
                return;
            }
            if (service != null) {
                stopWatchAsync(service);
            } else if (!binding) {
                status = "正在停止 Shizuku 看门狗";
                // Bind by the stable tag even if this app process was recreated. This reaches an
                // already-running daemon and prevents an immediate manual stop from being ignored.
                bindAttempts = 0;
                bindAsync();
            }
        } catch (Exception error) {
            binding = false;
            status = "看门狗停止失败";
            LogManager.warning("Failed to stop Shizuku watchdog: " + error.getClass().getSimpleName());
        }
    }

    private static void stopWatchAsync(final IShizukuCommandService target) {
        CONTROL_EXECUTOR.execute(() -> {
            if (!stopWhenConnected || watchRequested || target != service) return;
            try {
                target.stopWatch();
                status = "看门狗已停止";
            } catch (Throwable error) {
                status = "看门狗停止失败";
                LogManager.warning("Failed to stop Shizuku watchdog: " + error.getClass().getSimpleName());
            }
            if (target == service && stopWhenConnected && !watchRequested) unbindAndReset();
        });
    }

    private static void unbindAndReset() {
        try {
            if (bound) Shizuku.unbindUserService(args(), CONNECTION, true);
        } catch (Exception error) {
            LogManager.warning("Failed to release Shizuku service: " + error.getClass().getSimpleName());
        }
        synchronized (ShizukuWatchdogController.class) {
            service = null;
            bound = false;
            binding = false;
        }
    }

    public static String getStatus() { return status; }
}
