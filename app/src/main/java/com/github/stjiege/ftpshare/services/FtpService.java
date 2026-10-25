package com.github.stjiege.ftpshare.services;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.widget.Toast;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.PermissionChecker;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.BuildConfig;
import com.github.stjiege.ftpshare.MyApplication;
import com.github.stjiege.ftpshare.R;
import com.github.stjiege.ftpshare.activities.MainActivity;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.filesystem.CopyExecutor;
import com.github.stjiege.ftpshare.filesystem.MoveProgress;
import com.github.stjiege.ftpshare.filesystem.TransferBuffer;
import com.github.stjiege.ftpshare.filesystem.TransferSlots;
import com.github.stjiege.ftpshare.ftpserver.data.TcpSocketBuffer;
import com.github.stjiege.ftpshare.shizuku.ShizukuWatchdogController;
import com.github.stjiege.ftpshare.utils.AccessibilityKeepAlive;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.MySQLiteOpenHelper;
import com.github.stjiege.ftpshare.utils.PasswordCipher;
import com.github.stjiege.ftpshare.utils.FtpLogFtplet;
import com.github.stjiege.ftpshare.utils.FtpDiscoveryManager;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.AppAnalytics;
import com.github.stjiege.ftpshare.utils.StorageUtil;
import com.github.stjiege.ftpshare.workers.WorkScheduler;
import com.google.android.material.snackbar.Snackbar;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.listener.Listener;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.mina.core.session.IoSession;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FtpService extends Service {
    public static final int MESSAGE_START_FTP_COMPLETE = 1;
    public static final int MESSAGE_START_FTP_ERROR = -1;
    public static final int MESSAGE_WAKELOCK_ACQUIRE = 5;
    public static final int MESSAGE_WAKELOCK_RELEASE = 6;
    public static final int MESSAGE_WIFI_LOCK_RELOAD = 7;
    public static final int MESSAGE_MDNS_RELOAD = 8;
    // Do not dereference Application while this class is loaded by a separate user-service
    // process. The application ID is stable for this build.
    public static final String ACTION_STOP_SERVICE = BuildConfig.APPLICATION_ID + ":stop_ftp_service";
    public static final String ACTION_COPY_ADDRESS = BuildConfig.APPLICATION_ID + ":copy_ftp_address";
    private enum LifecycleState { STOPPED, START_REQUESTED, STARTING, RUNNING, STOPPING }

    private static final Object SERVICE_LOCK = new Object();
    private static final LinkedList<OnFTPServiceStatusChangedListener> listeners = new LinkedList<>();
    /** Serializes core start/stop across successive Service instances so a released port is never reused early. */
    private static final ExecutorService SERVER_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger nextId = new AtomicInteger(1);
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "FTP-Server-Lifecycle-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });
    /** Serializes account database reads/writes so the main thread never touches SQLite or the Keystore. */
    private static final ExecutorService ACCOUNT_IO_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger nextId = new AtomicInteger(1);
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "Account-DB-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    });

    /**
     * 传输与目录列举命令专用池。RETR/STOR/STOU/APPE 的整段执行体（含数十秒的阻塞拷贝）、
     * 以及 LIST/NLST/MLSD（含 accept 等待 + FUSE 全量目录遍历）跑在这里，而不是 MINA 的
     * 命令线程池里 —— 否则这些长阻塞命令会把线程占满，新连接的登录命令排不到队。
     * 见 {@code org.apache.ftpserver.impl.DefaultFtpHandler} 的注入点。
     *
     * <p>容量 = 「实际并发传输槽数」+ 余量：槽是硬闸（超出的传输在开文件前就被 450 拒绝），
     * 所以同时真正在跑的传输不会超过槽数；余量给 LIST/NLST/MLSD 留并发空间（它们不占槽，
     * 却占池线程，含最长 idleTime=60s 的 accept 等待）。core == max 且允许核心线程超时
     * 回收，空闲时不留驻线程。</p>
     *
     * <p>每次启动创建独立池，停止时取消排队任务。队列最多 128 个会话任务；
     * 饱和时拒绝连接，不把长传输退回 MINA 线程。同会话命令由有界 FIFO 保序。</p>
     */
    private ExecutorService transferExecutor;

    private static ExecutorService createTransferExecutor(int slots) {
        return new ThreadPoolExecutor(
            slots
                    + Constants.FTPConsts.FTP_TRANSFER_POOL_HEADROOM,
            slots
                    + Constants.FTPConsts.FTP_TRANSFER_POOL_HEADROOM,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(128),
            new ThreadFactory() {
                private final AtomicInteger nextId = new AtomicInteger(1);
                @Override public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "FTP-Transfer-" + nextId.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
            }) {
        {
            allowCoreThreadTimeOut(true);
        }
    };
    }

    // Guarded by SERVICE_LOCK. A manual stop also cancels this deferred restart.
    private static Context pendingStartContext;
    private static volatile FtpService activeService;
    private static volatile LifecycleState lifecycleState = LifecycleState.STOPPED;

    /**
     * 最近一次服务启动时 IPv6 双栈监听是否真正生效。
     * {@code false} 的两种来源：用户没开「允许从IPv6访问」；开了但绑定 "::" 失败已回退
     * IPv4。IP 地址列表用它保证展示的每个地址都真实可连。
     */
    private static volatile boolean ipv6Bound;

    /** See {@link #ipv6Bound}. Always false while no service has been started yet. */
    public static boolean isIpv6Bound() { return ipv6Bound; }

    private volatile FtpServer server;
    private PowerManager.WakeLock wakeLock;
    private final Handler serviceHandler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            try {
                if (!destroyed && activeService == FtpService.this) processMessage(msg);
            } catch (Exception error) {
                LogManager.error("FTP消息处理失败 what=" + msg.what, error);
            }
        }
    };
    private volatile boolean destroyed;
    /**
     * 这次停止是"应用自己决定停"的（用户点了停止、或启动失败自行收尾），不是被系统回收。
     *
     * <p>它决定 {@link Constants.PreferenceConsts#FTP_EXPECTED_RUNNING} 在 onDestroy 里要不要
     * 翻成 false。区分这两者的意义在于：用户主动停止的服务绝对不能被自愈任务再拉起来，
     * 而被系统回收的服务必须能被拉起来——后者正是自愈存在的理由。</p>
     */
    private volatile boolean expectedStop;
    /**
     * 前台服务通知的渠道 id。历史上从 IMPORTANCE_NONE 的渠道迁到 v3 才恢复可见，
     * 所以这个名字不要随手改——改了等于换一条全新渠道，用户原来的"关闭"选择会被重置。
     */
    private static final String NOTIFICATION_CHANNEL_ID = "ftp_service_v3";

    /**
     * CPU 锁的单次获取时长。锁的语义是"服务跑着就要一直持有"，所以不能只靠这一次超时——
     * 它由 {@link #keepAliveCheckRunnable}（10 分钟）周期性续期。取 15 分钟是给续期留 5 分钟
     * 余量：Doze 或一次长 GC 把续期推后一点也不会掉锁，而 release 消息真丢了，
     * 最多也就多持 15 分钟而不是永久。
     */
    private static final long WAKELOCK_TIMEOUT_MS = 15L * 60L * 1000L;

    private long foregroundStartedAt;
    private WifiManager.WifiLock wifiLock;
    private FtpDiscoveryManager discoveryManager;
    private final Handler dozeCheckHandler = new Handler(Looper.getMainLooper());
    private final Runnable dozeCheckRunnable = new Runnable() {
        @Override public void run() {
            if (!destroyed && isFTPServiceRunning()) {
                if (!isBatteryOptimizationIgnored(FtpService.this)) {
                    LogManager.warning("系统电池优化仍在生效；FTP 在 Doze 时可能失去网络连接");
                }
                dozeCheckHandler.postDelayed(this, 6L * 60L * 60L * 1000L);
            }
        }
    };

    private final Handler keepAliveCheckHandler = new Handler(Looper.getMainLooper());
    /**
     * The keep-alive grant can be revoked while the app is never opened. Polling the state here is
     * what makes the log warning reachable without the UI; the state itself is latched, so an
     * outage produces one line, not one per poll.
     */
    private final Runnable keepAliveCheckRunnable = new Runnable() {
        @Override public void run() {
            keepAliveCheckHandler.removeCallbacks(this);
            if (!destroyed && isFTPServiceRunning()) {
                try {
                    AccessibilityKeepAlive.evaluate(FtpService.this);
                } catch (Exception error) {
                    LogManager.debug("无障碍保活状态检查失败: " + error.getClass().getSimpleName());
                }
                renewWakeLockIfHeld();
                keepAliveCheckHandler.postDelayed(this, 10L * 60L * 1000L);
            }
        }
    };

    /**
     * 如果FTP Service 没有实例将创建实例并启动
     *
     * @return true 启动成功
     */
    public static boolean startService(@NonNull Context context) {
        if (com.github.stjiege.ftpshare.filesystem.UploadLeftoverSweep.isStartLocked()) {
            LogManager.info("FTP服务启动请求被忽略：正在清理上传残留");
            return false;
        }
        if (!beforeStartCheck(context)) {
            return false;
        }
        synchronized (SERVICE_LOCK) {
            if (lifecycleState == LifecycleState.STOPPING) {
                pendingStartContext = context.getApplicationContext();
                // 旧服务还在收尾、这次只是排队，同样要把意图记下来：万一进程在这段窗口里
                // 被杀，没有这个标志自愈就以为"用户没开过"。
                setExpectedRunning(context, true);
                return true;
            }
            pendingStartContext = null;
            if (lifecycleState == LifecycleState.START_REQUESTED
                    || lifecycleState == LifecycleState.STARTING
                    || lifecycleState == LifecycleState.RUNNING) {
                return true;
            }
            lifecycleState = LifecycleState.START_REQUESTED;
            try {
                if (Build.VERSION.SDK_INT >= 26)
                    context.startForegroundService(new Intent(context, FtpService.class));
                else context.startService(new Intent(context, FtpService.class));
                setExpectedRunning(context, true);
            } catch (RuntimeException e) {
                lifecycleState = LifecycleState.STOPPED;
                LogManager.error("请求启动FTP服务失败", e);
                throw e;
            }
        }
        return true;
    }

    /**
     * 给已持有的 CPU 锁续期。锁是 {@code setReferenceCounted(false)} 的，重复 acquire 只重置
     * 超时、不加计数，所以这里不需要先 release。
     */
    private void renewWakeLockIfHeld() {
        PowerManager.WakeLock lock = wakeLock;
        if (lock == null || !lock.isHeld()) return;
        try {
            lock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (Exception error) {
            LogManager.debug("续期CPU锁失败: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 记录用户的服务意图并同步自愈任务。
     *
     * <p>这个标志是自愈 Worker 唯一的依据：进程被杀后静态状态归零，只看
     * {@link #isFTPServiceRunning()} 无法区分「用户自己关的」和「被系统回收的」，
     * 少了它自愈就会把用户手动停掉的服务重新拉起来。</p>
     */
    private static void setExpectedRunning(@NonNull Context context, boolean running) {
        try {
            Context appContext = context.getApplicationContext();
            CommonUtils.getSettingSharedPreferences(appContext)
                    .edit().putBoolean(Constants.PreferenceConsts.FTP_EXPECTED_RUNNING, running).apply();
            WorkScheduler.syncSelfHealing(appContext);
        } catch (Exception error) {
            // 只影响兜底自愈能力，不能让它打断启动/停止流程。
            LogManager.debug("记录服务意图失败: " + error.getClass().getSimpleName());
        }
    }

    /**
     * 启动前检查读写权限以及用户模式下是否正确设置账户，如果上下文为Activity将会向Activity发送SnackBar，否则会通过
     * 上下文发送Toast
     *
     * @param context 启动服务的上下文
     * @return true - 检查正常  -false 账户错误或者没有权限
     */
    private static boolean beforeStartCheck(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!StorageUtil.hasAllFilesAccess()) {
                LogManager.warning("未授予所有文件访问权限，文件列表可能不完整");
            }
            // WRITE_EXTERNAL_STORAGE is ignored on Android 11+. Do not block
            // foreground-service startup because that legacy permission is denied.
            return true;
        }
        if (PermissionChecker.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PermissionChecker.PERMISSION_GRANTED) {
            if (context instanceof Activity) {
                final Activity activity = (Activity) context;
                Snackbar snackbar = Snackbar.make(activity.findViewById(android.R.id.content), activity.getResources().getString(R.string.permission_write_external), Snackbar.LENGTH_SHORT);
                snackbar.setAction(activity.getResources().getString(R.string.snackbar_action_goto), new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent appdetail = new Intent();
                        appdetail.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        appdetail.setData(Uri.fromParts("package", activity.getApplication().getPackageName(), null));
                        activity.startActivity(appdetail);
                    }
                });
                snackbar.show();
                activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
            } else {
                Toast.makeText(context, context.getResources().getString(R.string.permission_write_external), Toast.LENGTH_SHORT).show();
            }
            return false;
        }
        return true;
    }

    public static List<AccountItem> getUserAccountList(@NonNull Context context) {
        List<AccountItem> list = new ArrayList<>();
        SQLiteDatabase db = null;
        Cursor cursor = null;
        try {
            db = new MySQLiteOpenHelper(context).getReadableDatabase();
            cursor = db.rawQuery("select * from " + Constants.SQLConsts.TABLE_NAME, null);
            while (cursor.moveToNext()) {
                try {
                    AccountItem item = new AccountItem();
                    item.id = cursor.getInt(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_ID));
                    item.account = cursor.getString(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_ACCOUNT_NAME));
                    item.password = PasswordCipher.decrypt(cursor.getString(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_PASSWORD)));
                    item.path = cursor.getString(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_PATH));
                    item.writable = cursor.getInt(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_WRITABLE)) == 1;
                    item.fullStorage = cursor.getInt(cursor.getColumnIndexOrThrow(Constants.SQLConsts.COLUMN_FULL_STORAGE)) == 1;
                    list.add(item);
                } catch (Exception e) {
                    LogManager.warning("无法读取账户密码，已跳过该账户: " + e.getClass().getSimpleName());
                }
            }
        } catch (Exception e) {
            LogManager.error("读取FTP账户列表失败", e);
        } finally {
            if (cursor != null) {
                try { cursor.close(); } catch (Exception ignored) { }
            }
            if (db != null) {
                try { db.close(); } catch (Exception ignored) { }
            }
        }
        return list;
    }

    /** Callback for {@link #getUserAccountListAsync(Context, OnAccountsLoadedListener)}. */
    public interface OnAccountsLoadedListener {
        void onAccountsLoaded(@NonNull List<AccountItem> accounts);
    }

    /** Callback for {@link #saveAccountAsync(Context, AccountItem, Long, OnAccountSavedListener)}. */
    public interface OnAccountSavedListener {
        void onAccountSaved(boolean success, @Nullable Exception error);
    }

    /**
     * Loads accounts on a background thread (SQLite read + per-password Keystore decryption) and
     * posts the result back to the main thread. Never call {@link #getUserAccountList(Context)}
     * directly from the UI thread; use this instead.
     */
    public static void getUserAccountListAsync(@NonNull final Context context,
                                               @NonNull final OnAccountsLoadedListener listener) {
        final Context appContext = context.getApplicationContext();
        ACCOUNT_IO_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                final List<AccountItem> accounts = getUserAccountList(appContext);
                postToMainThread(new Runnable() {
                    @Override public void run() {
                        listener.onAccountsLoaded(accounts);
                    }
                });
            }
        });
    }

    /** Persists an account on a background thread (SQLite write + Keystore encryption). */
    public static void saveAccountAsync(@NonNull final Context context,
                                        @NonNull final AccountItem item,
                                        @Nullable final Long idUpdate,
                                        @NonNull final OnAccountSavedListener listener) {
        final Context appContext = context.getApplicationContext();
        final AccountItem snapshot = new AccountItem(item);
        ACCOUNT_IO_EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                boolean success = false;
                Exception error = null;
                try {
                    long result = MySQLiteOpenHelper.saveOrUpdateAccountItem2DB(appContext, snapshot, idUpdate);
                    boolean persisted = idUpdate == null ? result != -1L : result > 0L;
                    if (!persisted) {
                        throw new IllegalStateException(idUpdate == null
                                ? "Failed to insert FTP account"
                                : "FTP account to update was not found");
                    }
                    success = true;
                } catch (Exception e) {
                    error = e;
                    LogManager.error("保存访问账户失败", e);
                }
                final boolean finalSuccess = success;
                final Exception finalError = error;
                postToMainThread(new Runnable() {
                    @Override public void run() {
                        listener.onAccountSaved(finalSuccess, finalError);
                    }
                });
            }
        });
    }

    private static void postToMainThread(Runnable action) {
        new Handler(Looper.getMainLooper()).post(action);
    }

    /**
     * check if have ftp service instance and kill it
     */
    public static boolean stopService() {
        try {
            // 标记手动停止：服务真正停稳后由 onDestroy 的 stop 任务触发清扫。
            com.github.stjiege.ftpshare.filesystem.UploadLeftoverSweep.request();

            FtpService service;
            synchronized (SERVICE_LOCK) {
                pendingStartContext = null;
                service = activeService;
            }
            if (service != null) service.stopExpectedly();
            // 显式停止必须落盘：否则下一个自愈周期会把服务重新拉起来。
            setExpectedRunning(service != null ? service : MyApplication.getGlobalBaseContext(), false);
            return true;
        } catch (Exception e) {
            LogManager.error("请求停止FTP服务失败", e);
            return false;
        }
    }

    public static void sendEmptyMessage(int what) {
        try {
            FtpService service = activeService;
            if (service != null) service.serviceHandler.sendEmptyMessage(what);
        } catch (Exception e) {
            LogManager.error("发送FTP服务消息失败 what=" + what, e);
        }
    }

    public static void sendMessage(Message msg) {
        try {
            FtpService service = activeService;
            if (service != null) service.serviceHandler.sendMessage(msg);
        } catch (Exception e) {
            LogManager.error("发送FTP服务错误消息失败", e);
        }
    }

    public static void addOnFtpServiceStatusChangedListener(OnFTPServiceStatusChangedListener listener) {
        synchronized (SERVICE_LOCK) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    public static void removeOnFtpServiceStatusChangedListener(OnFTPServiceStatusChangedListener listener) {
        synchronized (SERVICE_LOCK) {
            listeners.remove(listener);
        }
    }

    public static boolean isFTPServiceRunning() {
        FtpService service = activeService;
        FtpServer currentServer = service == null ? null : service.server;
        return lifecycleState == LifecycleState.RUNNING && currentServer != null && !currentServer.isStopped();
    }

    /**
     * 当前**已登录**的 FTP 会话数；服务未运行或读取失败返回 -1。
     *
     * 只数 isLoggedIn() 的会话，与「最大连接数」（maxLogins）同口径 —— 若把握手阶段
     * 尚未登录的连接也算进去，读数可能超过上限，进度条看起来就像坏了。
     * 读的是监听器上现成的会话集合，不给 FTP 线程池增加任何工作。
     */
    public static int getActiveConnectionCount() {
        FtpService service = activeService;
        FtpServer currentServer = service == null ? null : service.server;
        if (!(currentServer instanceof DefaultFtpServer)) return -1;
        try {
            Listener listener = ((DefaultFtpServer) currentServer).getListener("default");
            if (listener == null) return -1;
            Set<FtpIoSession> sessions = listener.getActiveSessions();
            if (sessions == null) return -1;
            int count = 0;
            for (FtpIoSession session : sessions) {
                if (session != null && session.isLoggedIn()) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isBatteryOptimizationIgnored(@NonNull Context context) {
        // minSdk 23：再也走不到"低版本没有电池优化这一说"的早退分支。
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * A setting which changes the FTP core must wait for the old service to
     * release its listener and worker pool, not merely for the UI to say stopped.
     */
    public static boolean isFTPServiceFullyStopped() {
        synchronized (SERVICE_LOCK) {
            return lifecycleState == LifecycleState.STOPPED && activeService == null
                    && pendingStartContext == null;
        }
    }

    /** Save whitelist policy before any FTP start can snapshot it. */
    public static boolean updateIpWhitelistEnabled(@NonNull Context context, boolean enabled) {
        synchronized (SERVICE_LOCK) {
            if (lifecycleState != LifecycleState.STOPPED || activeService != null
                    || pendingStartContext != null) return false;
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, enabled);
            if (enabled) editor.putBoolean(Constants.PreferenceConsts.IPV6_ENABLED, false);
            editor.apply();
            return true;
        }
    }

    /** Save both mutually exclusive network options before startup can read either one. */
    public static boolean updateIpv6Enabled(@NonNull Context context, boolean enabled) {
        synchronized (SERVICE_LOCK) {
            if (lifecycleState != LifecycleState.STOPPED || activeService != null
                    || pendingStartContext != null) return false;
            SharedPreferences.Editor editor = context.getSharedPreferences(
                    Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE).edit()
                    .putBoolean(Constants.PreferenceConsts.IPV6_ENABLED, enabled);
            if (enabled) editor.putBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, false);
            editor.apply();
            return true;
        }
    }

    /** Keep the three implementation choices consistent with the service lifecycle. */
    public static boolean updatePerformanceMode(@NonNull Context context, boolean enabled) {
        synchronized (SERVICE_LOCK) {
            if (lifecycleState != LifecycleState.STOPPED || activeService != null
                    || pendingStartContext != null) return false;
            context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED, enabled)
                    .putBoolean(Constants.PreferenceConsts.NEW_COMMAND_ROUTER_ENABLED, enabled)
                    .putBoolean(Constants.PreferenceConsts.USE_LEGACY_DATA_CONNECTION, !enabled)
                    .apply();
            return true;
        }
    }

    public static boolean updateIpWhitelistEntries(@NonNull Context context, @NonNull Set<String> entries) {
        synchronized (SERVICE_LOCK) {
            if (lifecycleState != LifecycleState.STOPPED || activeService != null
                    || pendingStartContext != null) return false;
            context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                    .edit().putStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES,
                            new HashSet<>(entries)).apply();
            return true;
        }
    }

    public static String getFTPStatusDescription(Context context) {
        try {
            if (!isFTPServiceRunning())
                return context.getResources().getString(R.string.ftp_status_not_running);
            return context.getResources().getString(R.string.ftp_status_running_head) + CommonUtils.getFTPServiceDisplayAddress(context);
        } catch (Exception e) {
            LogManager.error("读取FTP服务状态失败", e);
        }
        return "";
    }

    /**
     * Charset frozen for the lifetime of the running server. The command decoder, the
     * response encoder and the directory-listing writer all read through here; reading the
     * preference live at each site let them disagree when the user changed the setting
     * mid-run (decoder kept the startup value while listings switched), which garbled
     * non-ASCII filenames and made files unreachable from clients. Changes to the charset
     * setting therefore take effect on the next server start.
     */
    private static volatile String activeFtpCharsetMode;

    /**
     * IoSession attribute holding a per-session charset override.
     *
     * <p>In adaptive mode the default is GBK and {@code OPTS UTF8} switches just that session
     * to UTF-8. In forced UTF-8 or GBK mode, the server keeps the selected charset for every
     * session and rejects attempts to switch away from it.</p>
     */
    private static final String ATTR_SESSION_CHARSET = "ftpshare.session.charset";

    public static String getActiveFtpCharsetMode() {
        String mode = activeFtpCharsetMode;
        if (mode == null) {
            mode = MyApplication.getGlobalBaseContext()
                    .getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                    .getString(Constants.PreferenceConsts.CHARSET_TYPE,
                            Constants.PreferenceConsts.CHARSET_TYPE_DEFAULT);
        }
        if (Constants.Charset.CHAR_UTF.equals(mode) || Constants.Charset.CHAR_GBK.equals(mode)) {
            return mode;
        }
        return Constants.Charset.CHARSET_AUTO;
    }

    /** The configured charset used when a session has no negotiated override. */
    public static String getActiveFtpCharset() {
        return Constants.Charset.CHAR_UTF.equals(getActiveFtpCharsetMode())
                ? Constants.Charset.CHAR_UTF : Constants.Charset.CHAR_GBK;
    }

    /** UTF-8 is advertised in adaptive mode and when it is the forced server charset. */
    public static boolean isUtf8FeatureEnabled() {
        return !Constants.Charset.CHAR_GBK.equals(getActiveFtpCharsetMode());
    }

    /**
     * Charset to use for this connection: the {@code OPTS UTF8} override if the client asked
     * for one, otherwise the server-wide setting. Read by the control-connection decoder, the
     * response encoder and the listing writer, so all three agree per session.
     */
    public static String getSessionCharset(IoSession session) {
        if (session != null) {
            Object override = session.getAttribute(ATTR_SESSION_CHARSET);
            if (override instanceof String) return (String) override;
        }
        return getActiveFtpCharset();
    }

    /** Sets or clears ({@code null}) the per-session charset override. */
    public static void setSessionCharset(IoSession session, String charset) {
        if (session == null) return;
        if (charset == null) session.removeAttribute(ATTR_SESSION_CHARSET);
        else session.setAttribute(ATTR_SESSION_CHARSET, charset);
    }

    public static void refreshOngoingNotification() {
        FtpService service = activeService;
        if (service == null) return;
        CommonUtils.updateResourcesOfContext(service);
        service.makeThisForeground(service.getResources().getString(R.string.notification_title),
                service.getResources().getString(R.string.ftp_status_running_head) + CommonUtils.getFTPServiceDisplayAddress(service));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        expectedStop = false;
        foregroundStartedAt = System.currentTimeMillis();
        LogManager.start(this);
        LogManager.debug("FTP服务进程创建");
        synchronized (SERVICE_LOCK) {
            if (activeService != null && activeService != this) {
                LogManager.error("拒绝重复创建FTP服务实例", null);
                stopSelf();
                return;
            }
            activeService = this;
            lifecycleState = LifecycleState.STARTING;
        }
        MoveProgress.setListener((active, label, percent) ->
                serviceHandler.post(() -> updateMoveProgressNotification(active, label, percent)));
        discoveryManager = new FtpDiscoveryManager(this);
        beginStartup();
    }

    /** Shared by a fresh {@code onCreate} and by a watchdog-delivered start command. */
    private void beginStartup() {
        if (!makeThisForeground(getResources().getString(R.string.app_name), getResources().getString(R.string.attention_opening_ftp))) {
            stopExpectedly();
            return;
        }
        if (!beforeStartCheck(this)) {
            stopExpectedly();
            return;
        }
        SERVER_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // Read accounts off the main thread: SQLite + per-password Keystore decryption.
                    final List<AccountItem> accountItems = getUserAccountList(FtpService.this);
                    if (startFTPService(accountItems) && !destroyed) {
                        sendEmptyMessage(MESSAGE_START_FTP_COMPLETE);
                    }
                } catch (Exception e) {
                    LogManager.error("FTP服务异步启动失败", e);
                    Message msg = new Message();
                    msg.what = MESSAGE_START_FTP_ERROR;
                    msg.obj = e;
                    sendMessage(msg);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogManager.debug("FTP服务收到启动命令 startId=" + startId);
        // The watchdog can only deliver `am start-foreground-service`. If this process still owns a
        // service instance that is not actually running, Android will not call onCreate again, so
        // the startup has to be driven from here; otherwise recovery silently does nothing.
        boolean needsStart = false;
        synchronized (SERVICE_LOCK) {
            if (activeService == this
                    && lifecycleState != LifecycleState.STARTING
                    && lifecycleState != LifecycleState.START_REQUESTED
                    && lifecycleState != LifecycleState.STOPPING
                    && !isFTPServiceRunning()) {
                lifecycleState = LifecycleState.STARTING;
                needsStart = true;
            }
        }
        if (needsStart) {
            LogManager.info("FTP服务收到恢复命令，重新执行启动流程");
            beginStartup();
        }
        // FTP is stopped only by a user action or a protected external-control request.
        // Do not let Android recreate it later without a fresh foreground-start
        // request; that can trigger ForegroundServiceDidNotStartInTimeException.
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private boolean startFTPService(List<AccountItem> list) throws Exception {
        SharedPreferences settings = getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);
        FtpServerFactory factory = new FtpServerFactory();
        factory.getFtplets().put("audit", new FtpLogFtplet());
        // 在监听器（及其编解码工厂）构建之前，先冻结协议字符集。
        activeFtpCharsetMode = settings.getString(Constants.PreferenceConsts.CHARSET_TYPE,
                Constants.PreferenceConsts.CHARSET_TYPE_DEFAULT);
        Set<String> fullStorageAccounts = AccountRegistrar.registerAccounts(factory, list, this, settings);

        boolean newFilesystem = settings.getBoolean(
                Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED,
                Constants.PreferenceConsts.NEW_FILESYSTEM_ENABLED_DEFAULT);
        org.apache.ftpserver.ftplet.FileSystemFactory fileSystem;
        if (!newFilesystem) {
            // 兼容回退路径：MultiRoot + SafeUpload/SafeMove 代理栈。
            if (fullStorageAccounts.isEmpty()) {
                fileSystem = new org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory();
            } else {
                fileSystem = new com.github.stjiege.ftpshare.filesystem.MultiRootFileSystemFactory(
                        getApplicationContext(), fullStorageAccounts);
            }
            fileSystem = new com.github.stjiege.ftpshare.filesystem.SafeUploadFileSystemFactory(
                    new com.github.stjiege.ftpshare.filesystem.SafeMoveFileSystemFactory(fileSystem));
        } else {
            // 新路径：去掉动态代理，staged upload / 移动直接由 LocalFileSystem 处理。
            fileSystem = new com.github.stjiege.ftpshare.filesystem.ApacheFileSystemAdapter(
                    getApplicationContext(), fullStorageAccounts);
            LogManager.info("已启用新文件系统 LocalFileSystem（legacy=false）");
        }
        factory.setFileSystem(fileSystem);

        ListenerFactory lfactory = new ListenerFactory();
        boolean ftps = settings.getBoolean(Constants.PreferenceConsts.FTPS_ENABLED, false);
        boolean chacha20 = settings.getBoolean(Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED,
                Constants.PreferenceConsts.FTPS_CHACHA20_PREFERRED_DEFAULT);
        FtpsConfiguration tls = ftps ? new FtpsConfiguration(getApplicationContext(), chacha20) : null;
        if (tls != null) {
            lfactory.setSslConfiguration(tls);
            lfactory.setImplicitSsl(false);
            // 开关开了但运行时不支持该套件（如 Android 6）时必须留痕，否则用户以为生效了。
            if (chacha20) {
                LogManager.info(tls.chaCha20Active()
                        ? "FTPS ChaCha20-Poly1305 已置为首选套件（实验室）"
                        : "FTPS ChaCha20 开关已开，但当前运行时不支持该套件，已回退 AES");
            }
        }

        boolean newCommandRouter = settings.getBoolean(
                Constants.PreferenceConsts.NEW_COMMAND_ROUTER_ENABLED,
                Constants.PreferenceConsts.NEW_COMMAND_ROUTER_ENABLED_DEFAULT);
        if (!newCommandRouter) {
            TransferCommandGuard.install(factory);
            // 旧路径：由 FtpsCommandGuard 注册 HASH 扩展命令，并在 FTPS 模式下做 TLS 强制。
            FtpsCommandGuard.install(factory, tls != null);
        } else {
            factory.setCommandFactory(new com.github.stjiege.ftpshare.ftpserver.command.CommandRouter(
                    tls != null));
            LogManager.info("已启用新命令调度 CommandRouter（legacy=false）");
        }
        // 显式绑定通配地址。默认 IPv4 通配 0.0.0.0：Apache FtpServer 若留空会把服务地址
        // 解析成双栈设备上的 IPv6 通配 "::"，在禁用或限制 IPv6 的 ROM/网络下绑定
        // "[::]:port" 会抛 FtpServerConfigurationException("Failed to bind to address ::")。
        // 用户在「安全与目录」开启「允许从IPv6访问」后改绑 "::"（IPv6 双栈通配，
        // Android 默认 bindv6only=0，同时接受 IPv4 映射连接）；绑定失败在 start() 处
        // 自动回退 0.0.0.0（见下方 startWithFallback）。
        boolean ipv6Enabled = settings.getBoolean(Constants.PreferenceConsts.IPV6_ENABLED,
                Constants.PreferenceConsts.IPV6_ENABLED_DEFAULT);
        lfactory.setServerAddress(ipv6Enabled ? "::" : "0.0.0.0");
        lfactory.setPort(settings.getInt(Constants.PreferenceConsts.PORT_NUMBER, Constants.PreferenceConsts.PORT_NUMBER_DEFAULT)); //设置端口号 非ROOT不可使用1024以下的端口
        // Default 0 preserves long transfers. Nonzero values enable MINA control-session idle
        // expiry; data-channel traffic does not necessarily refresh that control-session clock.
        int idleTimeout = Constants.PreferenceConsts.normalizeIdleTimeout(settings.getInt(
                Constants.PreferenceConsts.FTP_IDLE_TIMEOUT,
                Constants.PreferenceConsts.FTP_IDLE_TIMEOUT_DEFAULT));
        lfactory.setIdleTimeout(idleTimeout);
        IpWhitelistConfigurator.apply(lfactory, settings);
        // Windows 资源管理器与很多 NAT/防火墙组合需要一个稳定的被动数据端口范围。范围刻意取高，
        // 免 root 且不与命令端口冲突。
        //
        // 范围从 2000 收敛到 64：FTPS 会话复用命中率的需要。按 host:port 索引会话缓存的
        // 客户端（libcurl 等）只有在数据端口复用时才可能命中 TLS 会话复用——2000 个端口
        // 几乎保证每条数据连接都换新端口，复用必然 miss；64 个端口让端口在几次传输内就被
        // 复用，命中率才上得来（另两道防线：会话票证与 ID 缓存，见 FtpsConfiguration）。
        // 并发容量不是约束：数据连接并发上限 = 传输槽（FTP_TRANSFER_SLOTS_MAX 10）+ 列目录
        // 余量（FTP_TRANSFER_POOL_HEADROOM 4），64 个端口是 4 倍余量；TIME_WAIT 已由
        // newServerSocket 的 SO_REUSEADDR 解决（当初取 2000 宽的理由），海量小文件不再
        // 依赖宽端口窗口。
        DataConnectionConfigurationFactory dataConfig = new DataConnectionConfigurationFactory();
        dataConfig.setPassivePorts("50000-50063");
        if (tls != null) dataConfig.setSslConfiguration(tls);
        dataConfig.setPassiveIpCheck(true);
        // 主动模式（PORT）默认关闭，只允许被动模式；「安全与目录」里显式开启后才接受
        // PORT 命令。数据连接的主动分支（服务端 connect 客户端地址）在
        // IODataConnectionFactory.createDataSocket 中是现成实现，这里只是放行。
        dataConfig.setActiveEnabled(settings.getBoolean(
                Constants.PreferenceConsts.ACTIVE_MODE_ENABLED,
                Constants.PreferenceConsts.ACTIVE_MODE_ENABLED_DEFAULT));
        // PORT/EPRT must name the same peer as the authenticated control connection.
        // Without this, an account can make the phone connect to arbitrary LAN addresses
        // (the classic FTP bounce issue).  This only constrains active mode; passive mode
        // keeps its separate passiveIpCheck above.
        dataConfig.setActiveIpCheck(true);
        // 它同时是数据 socket 的空闲超时和 SO_TIMEOUT（读写超时）。慢速或卡住的客户端因此
        // 无法永远占住一个传输 worker：60 秒无活动即中止传输并释放 worker，而不是默认的 300 秒。
        dataConfig.setIdleTime(60);
        lfactory.setDataConnectionConfiguration(dataConfig.createDataConnectionConfiguration());
        factory.addListener("default", lfactory.createListener());
        boolean legacyDataConnection = settings.getBoolean(
                Constants.PreferenceConsts.USE_LEGACY_DATA_CONNECTION,
                Constants.PreferenceConsts.USE_LEGACY_DATA_CONNECTION_DEFAULT);
        com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext.configure(
                factory.getListener("default"), legacyDataConnection);
        LogManager.info(legacyDataConnection ? "数据连接使用旧版 IODataConnectionFactory"
                : "数据连接使用新版 DataConnectionManager / SocketDataConnection");
        LogManager.info("FTP被动数据端口范围 50000-50063");
        FtpConnectionConfig connectionConfig = new FtpConnectionConfig();
        connectionConfig.setAnonymousEnabled(settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT));
        connectionConfig.setMaxAnonymousLogins(settings.getInt(Constants.PreferenceConsts.MAX_ANONYMOUS_NUM,
                Constants.PreferenceConsts.MAX_ANONYMOUS_NUM_DEFAULT));
        connectionConfig.setMaxLogins(settings.getInt(Constants.PreferenceConsts.MAX_LOGIN_NUM,
                Constants.PreferenceConsts.MAX_LOGIN_NUM_DEFAULT));
        // 登录闸门的有效取值必须落到日志里：客户端"连不上"时，这三个数是第一现场。
        // 只记录、不改写——真要动协议行为得先有证据。
        LogManager.info("登录闸门：匿名="
                + (connectionConfig.isAnonymousLoginEnabled() ? "启用" : "关闭")
                + " 匿名上限=" + connectionConfig.getMaxAnonymousLogins()
                + " 全局上限=" + connectionConfig.getMaxLogins()
                + " 单账号上限=" + Constants.FTPConsts.FTP_MAX_LOGINS_PER_ACCOUNT);
        if (connectionConfig.isAnonymousLoginEnabled()
                && connectionConfig.getMaxAnonymousLogins() <= 0) {
            // Apache 的匿名判据没有 `!= 0` 保护（USER/PASS 都是 currAnon >= maxAnon），
            // 取 0 等于"一条都不许"，与设置页"0 表示不限"的直觉正好相反。
            LogManager.warning("匿名上限为 0，Apache 会拒绝全部匿名登录（不是不限）；请到「服务器设置」改成大于 0");
        }
        int configuredWorkers = settings.getInt(Constants.PreferenceConsts.FTP_WORKER_THREADS,
                Constants.PreferenceConsts.FTP_WORKER_THREADS_DEFAULT);
        // 纠正低版本升级留下来的越界值（旧范围 4–32，新范围 8–20）。
        configuredWorkers = Math.max(Constants.PreferenceConsts.FTP_WORKER_THREADS_MIN,
                Math.min(Constants.PreferenceConsts.FTP_WORKER_THREADS_MAX, configuredWorkers));
        connectionConfig.setMaxThreads(configuredWorkers);
        LogManager.info("FTP工作线程数 " + configuredWorkers);
        factory.setConnectionConfig(connectionConfig);
        // 传输槽只在这里调整：此刻服务还没开始监听，绝不会有传输在飞，resize 不会与归还竞争。
        // 槽在 TransferCommandGuard 中、打开文件之前扣减，对所有账户生效，与文件系统实现无关。
        int transferSlots = Math.max(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MIN,
                Math.min(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_MAX,
                        settings.getInt(Constants.PreferenceConsts.FTP_TRANSFER_SLOTS,
                                Constants.PreferenceConsts.FTP_TRANSFER_SLOTS_DEFAULT)));
        TransferSlots.configure(transferSlots);
        // 块大小每次开传时才读，所以这里只是把持久化的档位同步进内存（进程刚起来时持有者还带着默认值）。
        TransferBuffer.configure(settings.getInt(Constants.PreferenceConsts.FTP_TRANSFER_BUFFER,
                Constants.PreferenceConsts.FTP_TRANSFER_BUFFER_DEFAULT));
        TcpSocketBuffer.configure(settings.getInt(Constants.PreferenceConsts.FTP_SOCKET_BUFFER_REQUEST,
                Constants.PreferenceConsts.FTP_SOCKET_BUFFER_SYSTEM_DEFAULT));

        if (destroyed || activeService != this) {
            LogManager.info("FTP服务已在启动过程中停止，取消创建服务器");
            return false;
        }
        // 传输命令走独立池：必须在 start() 之前注入，否则首批连接仍会用命令线程池。
        // DefaultFtpHandler 在 factory.createServer() 时初始化，并在 init() 中快照池引用，
        // 因此注入必须先于 createServer，而不只是先于 start()。
        transferExecutor = createTransferExecutor(transferSlots);
        org.apache.ftpserver.impl.DefaultFtpHandler.setTransferExecutor(transferExecutor);
        // 跨卷移动走自己的专用池：拷贝是分钟级操作，既不能压在 MINA 的 I/O 线程上
        // （那会让整条 processor 上的会话断连），也不该挤占按秒设计的传输池。
        org.apache.ftpserver.impl.DefaultFtpHandler.setCopyExecutor(CopyExecutor.get());
        FtpServer candidate = factory.createServer();
        server = candidate;
        try {
            candidate.start();
            ipv6Bound = ipv6Enabled;
        } catch (RuntimeException error) {
            if (!ipv6Enabled) throw error;
            // 「允许从IPv6访问」开启但绑定 "::" 失败（部分 ROM 不支持/禁用 IPv6 通配）：
            // 回退 IPv4 单栈再试一次，保证 FTP 服务仍然可用；再失败走既有错误上报路径。
            LogManager.warning("IPv6 绑定失败，已回退 IPv4 单栈: " + error.getClass().getSimpleName());
            ipv6Bound = false;
            lfactory.setServerAddress("0.0.0.0");
            // Listener is an immutable snapshot made by createListener().  Merely changing
            // lfactory here leaves factory's existing "default" listener bound to "::".
            // Replace that same map entry before rebuilding the server.
            factory.addListener("default", lfactory.createListener());
            // 重建前注入新池，因为 createServer() 会让新 handler 快照当前池引用。
            ExecutorService abandonedExecutor = transferExecutor;
            transferExecutor = createTransferExecutor(transferSlots);
            org.apache.ftpserver.impl.DefaultFtpHandler.setTransferExecutor(transferExecutor);
            org.apache.ftpserver.impl.DefaultFtpHandler.setCopyExecutor(CopyExecutor.get());
            candidate = factory.createServer();
            server = candidate;
            // 首个监听器启动失败时没有 accept 连接；释放它对应的未使用池。
            if (abandonedExecutor != null) abandonedExecutor.shutdownNow();
            candidate.start();
        }
        synchronized (SERVICE_LOCK) {
            if (destroyed || activeService != this || lifecycleState == LifecycleState.STOPPING) {
                LogManager.info("FTP监听器已启动但服务正在停止，交由停止队列释放");
                return false;
            }
            lifecycleState = LifecycleState.RUNNING;
        }
        LogManager.info("FTP服务已启动 port=" + settings.getInt(Constants.PreferenceConsts.PORT_NUMBER, Constants.PreferenceConsts.PORT_NUMBER_DEFAULT));
        AppAnalytics.logServiceState(this, true);
        return true;
    }

    private boolean makeThisForeground(String title, String content) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26) {
                // The old channel used IMPORTANCE_NONE in earlier builds. Android keeps
                // channel importance immutable, so use a new id to recover visibility.
                NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID
                        , getResources().getString(R.string.notification_channel_foreground_service)
                        , NotificationManager.IMPORTANCE_LOW);
                manager.createNotificationChannel(channel);
            }
            // 通知"看不见"有两类互不相干的原因，都不影响服务本身运行，只影响用户能否看到：
            // 应用总开关被关，或这一条渠道被单独关掉（权限被拒的情况在进主界面时已请求过）。
            // 分开记一条日志，否则用户只能看到"服务在跑、通知栏却是空的"，无从下手。
            if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
                LogManager.warning("应用通知总开关已关闭，前台服务通知不会显示（服务仍正常运行）");
            } else if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);
                if (existing != null && existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                    LogManager.warning("通知渠道 " + NOTIFICATION_CHANNEL_ID
                            + " 已被关闭，前台服务通知不会显示（服务仍正常运行）");
                }
            }
            if (foregroundStartedAt == 0L) foregroundStartedAt = System.currentTimeMillis();
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
            builder.setSmallIcon(R.drawable.ic_ex_24dp);
            builder.setContentTitle(title);
            builder.setContentText(content);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setOngoing(true);
            builder.setAutoCancel(false);
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
            builder.setWhen(foregroundStartedAt);
            builder.setShowWhen(true);
            builder.setUsesChronometer(true);
            // minSdk 23：FLAG_IMMUTABLE 无条件加上（API 23 引入，此前需要版本判断）。
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Intent stopIntent = new Intent(this, StopServiceReceiver.class);
            stopIntent.setAction(ACTION_STOP_SERVICE);
            builder.addAction(R.drawable.ic_stop, getResources().getString(R.string.word_stop), PendingIntent.getBroadcast(this, 0, stopIntent, pendingIntentFlags));
            Intent copyIntent = new Intent(this, CopyAddressReceiver.class);
            copyIntent.setAction(ACTION_COPY_ADDRESS);
            builder.addAction(R.drawable.ic_ex_24dp, getResources().getString(R.string.notification_action_copy_address), PendingIntent.getBroadcast(this, 1, copyIntent, pendingIntentFlags));
            if (!isBatteryOptimizationIgnored(this)) {
                Intent protectionIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                builder.addAction(R.drawable.ic_ex_24dp, getResources().getString(R.string.notification_action_background_protection),
                        PendingIntent.getActivity(this, 2, protectionIntent, pendingIntentFlags));
            }
            builder.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingIntentFlags));
            startForeground(1, builder.build());
            return true;
        } catch (Exception e) {
            LogManager.error("前台服务通知创建或 startForeground 失败", e);
            return false;
        }
    }

    /**
     * 移动操作进度回调：把前台通知切换为进度条样式，无移动时恢复常规运行通知。
     */
    private void updateMoveProgressNotification(boolean active, String label, int percent) {
        if (foregroundStartedAt == 0L) return;
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID);
            builder.setSmallIcon(R.drawable.ic_ex_24dp);
            builder.setPriority(NotificationCompat.PRIORITY_LOW);
            builder.setOngoing(true);
            builder.setAutoCancel(false);
            builder.setCategory(NotificationCompat.CATEGORY_SERVICE);
            builder.setWhen(foregroundStartedAt);
            builder.setShowWhen(true);
            builder.setUsesChronometer(true);
            if (active) {
                builder.setContentTitle(getResources().getString(R.string.notification_title)
                        + " - " + getResources().getString(R.string.notification_moving));
                builder.setContentText(label + " " + percent + "%");
                builder.setProgress(100, percent, false);
            } else {
                builder.setContentTitle(getResources().getString(R.string.notification_title));
                builder.setContentText(getResources().getString(R.string.ftp_status_running_head)
                        + CommonUtils.getFTPServiceDisplayAddress(this));
                builder.setProgress(0, 0, false);
            }
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Intent stopIntent = new Intent(this, StopServiceReceiver.class);
            stopIntent.setAction(ACTION_STOP_SERVICE);
            builder.addAction(R.drawable.ic_stop, getResources().getString(R.string.word_stop),
                    PendingIntent.getBroadcast(this, 0, stopIntent, pendingIntentFlags));
            Intent copyIntent = new Intent(this, CopyAddressReceiver.class);
            copyIntent.setAction(ACTION_COPY_ADDRESS);
            builder.addAction(R.drawable.ic_ex_24dp, getResources().getString(R.string.notification_action_copy_address),
                    PendingIntent.getBroadcast(this, 1, copyIntent, pendingIntentFlags));
            if (!isBatteryOptimizationIgnored(this)) {
                Intent protectionIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                builder.addAction(R.drawable.ic_ex_24dp, getResources().getString(R.string.notification_action_background_protection),
                        PendingIntent.getActivity(this, 2, protectionIntent, pendingIntentFlags));
            }
            builder.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), pendingIntentFlags));
            startForeground(1, builder.build());
        } catch (Exception e) {
            LogManager.error("更新移动进度通知失败", e);
        }
    }

    private void processMessage(Message msg) {
        try {
            switch (msg.what) {
                default:
                    break;
                case MESSAGE_START_FTP_COMPLETE: {
                    if (!getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE).getBoolean(
                            Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED,
                            Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED_DEFAULT)) {
                        sendEmptyMessage(MESSAGE_WAKELOCK_ACQUIRE);
                    } else {
                        sendEmptyMessage(MESSAGE_WAKELOCK_RELEASE);
                    }
                    updateWifiLock();
                    updateMdnsRegistration();
                    // The guard is deliberately armed only after the FTP listener is live. It is
                    // a separate Shizuku daemon, so an unexpected app-process death can be seen.
                    ShizukuWatchdogController.startIfEnabled(this);
                    dozeCheckHandler.removeCallbacks(dozeCheckRunnable);
                    dozeCheckHandler.post(dozeCheckRunnable);
                    keepAliveCheckHandler.removeCallbacks(keepAliveCheckRunnable);
                    keepAliveCheckHandler.post(keepAliveCheckRunnable);

                    // 到这里服务是真的起来了，再确认一次标志：万一用户在 onCreate 完成之前
                    // 点过停止（那时 activeService 还是 null，标志被写成了 false），
                    // 这里会把"实际在跑"这个事实纠正回来，否则自愈会一直认为不用管。
                    setExpectedRunning(FtpService.this, true);
                    notifyStarted();

                    makeThisForeground(getResources().getString(R.string.notification_title),
                            getResources().getString(R.string.ftp_status_running_head) + CommonUtils.getFTPServiceDisplayAddress(this));
                }
                break;
                case MESSAGE_START_FTP_ERROR: {
                    notifyStartError((Exception) msg.obj);
                    // 启动失败通常是持久性原因（端口被占、配置不合法），清掉意图标志
                    // 免得自愈任务每 15 分钟重试一次注定失败的事。
                    setExpectedRunning(FtpService.this, false);
                    stopExpectedly();
                }
                break;
                case MESSAGE_WAKELOCK_ACQUIRE: {
                    try {
                        try {
                            if (wakeLock != null) wakeLock.release();
                        } catch (Exception error) { LogManager.warning("释放旧CPU锁失败: " + error.getClass().getSimpleName()); }
                        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FTP Share:ftp_wake_lock");
                        // 非引用计数：续期时重复 acquire 不会累加计数，一次 release 就能真正放开。
                        wakeLock.setReferenceCounted(false);
                        // 带超时获取，由 keepAliveCheckRunnable 周期续期。原来的无参 acquire()
                        // 一旦 release 消息丢失就会永久持锁（进程没死、锁也不放），那是最坏的
                        // 一种耗电——用户看得到 FTP 在跑，看不出 CPU 从不休眠。
                        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
                    } catch (Exception e) {
                        LogManager.error("获取CPU锁失败", e);
                    }

                }
                break;
                case MESSAGE_WAKELOCK_RELEASE: {
                    try {
                        if (wakeLock != null) wakeLock.release();
                        wakeLock = null;
                    } catch (Exception e) {
                        LogManager.error("释放CPU锁失败", e);
                    }
                }
                break;
                case MESSAGE_WIFI_LOCK_RELOAD: {
                    updateWifiLock();
                }
                break;
                case MESSAGE_MDNS_RELOAD: {
                    updateMdnsRegistration();
                }
                break;
            }
        } catch (Exception e) {
            LogManager.error("FTP服务消息处理失败 what=" + msg.what, e);
        }
    }

    @Override
    public void onDestroy() {
        LogManager.info("FTP服务正在停止");
        AppAnalytics.logServiceState(this, false);
        destroyed = true;
        MoveProgress.setListener(null);
        dozeCheckHandler.removeCallbacks(dozeCheckRunnable);
        keepAliveCheckHandler.removeCallbacks(keepAliveCheckRunnable);
        if (discoveryManager != null) discoveryManager.unregister();
        releaseWifiLock();
        synchronized (SERVICE_LOCK) {
            if (activeService == this) activeService = null;
            lifecycleState = LifecycleState.STOPPING;
        }
        SERVER_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                // startFTPService and this stop task are both confined to SERVER_EXECUTOR.
                // This serial ordering prevents a newly started listener from being stopped here.
                FtpServer serverToStop = server;
                server = null;
                try {
                    if (serverToStop != null) serverToStop.stop();
                } catch (Exception e) {
                    LogManager.error("停止旧FTP服务器实例失败", e);
                } finally {
                    if (transferExecutor != null) {
                        transferExecutor.shutdownNow();
                        transferExecutor = null;
                    }
                    org.apache.ftpserver.impl.DefaultFtpHandler.setTransferExecutor(null);
                    // 拷贝池不关：线程是守护线程且 60 秒闲置回收，强行中断会留下半个临时文件。
                    org.apache.ftpserver.impl.DefaultFtpHandler.setCopyExecutor(null);
                    synchronized (SERVICE_LOCK) {
                        if (activeService == null) lifecycleState = LifecycleState.STOPPED;
                    }
                    if (expectedStop) {
                        com.github.stjiege.ftpshare.filesystem.UploadLeftoverSweep.runIfRequested(FtpService.this);
                    }
                    postToMainThread(FtpService::startPendingService);
                }
            }
        });

        try {
            if (wakeLock != null) {
                wakeLock.release();
                wakeLock = null;
            }
        } catch (Exception e) {
            LogManager.error("释放CPU锁失败", e);
        }

        // 只有"应用自己决定停"才把持久化标志翻成 false。被系统回收时不翻，
        // 自愈任务才有的可拉；而用户主动停掉的服务不该被拉起来，靠的就是这一行。
        // pendingStartContext 非空说明已经有人排了一次重启，那就别抢在它前面写 false。
        boolean stopIsFinal;
        synchronized (SERVICE_LOCK) {
            stopIsFinal = expectedStop && pendingStartContext == null;
        }
        if (stopIsFinal) setExpectedRunning(this, false);

        notifyDestroyed();
        super.onDestroy();
    }

    private static void startPendingService() {
        synchronized (SERVICE_LOCK) {
            if (lifecycleState != LifecycleState.STOPPED || activeService != null) return;
            Context context = pendingStartContext;
            pendingStartContext = null;
            if (context != null) {
                try { startService(context); }
                catch (RuntimeException error) { notifyStartError(error); }
            }
        }
    }

    /** Stops a deliberately ended server without letting the optional Shizuku guard revive it. */
    private void stopExpectedly() {
        expectedStop = true;
        try {
            ShizukuWatchdogController.stop(this);
        } catch (Exception error) {
            LogManager.warning("停止 Shizuku 看门狗失败: " + error.getClass().getSimpleName());
        }
        stopSelf();
    }

    public interface OnFTPServiceStatusChangedListener {
        void onFTPServiceStarted();

        void onFTPServiceStartError(Exception e);

        void onFTPServiceDestroyed();
    }

    private static List<OnFTPServiceStatusChangedListener> getListenerSnapshot() {
        synchronized (SERVICE_LOCK) {
            return new ArrayList<>(listeners);
        }
    }

    /**
     * One faulty listener must not starve the others: without this isolation a single NPE (a tile
     * that is no longer listening, a fragment already detached) silently ends the whole dispatch,
     * so the remaining UI never learns that the server stopped.
     */
    private static void notifyStarted() {
        for (OnFTPServiceStatusChangedListener listener : getListenerSnapshot()) {
            try {
                listener.onFTPServiceStarted();
            } catch (Throwable error) {
                LogManager.warning("通知监听器失败(" + listener.getClass().getSimpleName() + "): "
                        + error.getClass().getSimpleName());
            }
        }
    }

    private static void notifyStartError(Exception cause) {
        for (OnFTPServiceStatusChangedListener listener : getListenerSnapshot()) {
            try {
                listener.onFTPServiceStartError(cause);
            } catch (Throwable error) {
                LogManager.warning("通知监听器失败(" + listener.getClass().getSimpleName() + "): "
                        + error.getClass().getSimpleName());
            }
        }
    }

    private static void notifyDestroyed() {
        for (OnFTPServiceStatusChangedListener listener : getListenerSnapshot()) {
            try {
                listener.onFTPServiceDestroyed();
            } catch (Throwable error) {
                LogManager.warning("通知监听器失败(" + listener.getClass().getSimpleName() + "): "
                        + error.getClass().getSimpleName());
            }
        }
    }

    public static class StopServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP_SERVICE.equalsIgnoreCase(intent.getAction())) {
                stopService();
            }
        }
    }

    public static class CopyAddressReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_COPY_ADDRESS.equals(intent.getAction())) return;
            try {
                String address = CommonUtils.getFTPServiceDisplayAddress(context);
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("FTP address", address));
                LogManager.info("已将 FTP 地址复制到剪贴板");
            } catch (Exception error) {
                LogManager.error("复制FTP地址失败", error);
            }
        }
    }

    private void updateWifiLock() {
        boolean enabled = !getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED,
                        Constants.PreferenceConsts.PERFORMANCE_OPTIMIZATION_DISABLED_DEFAULT);
        boolean wifiActive = false;
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            wifiActive = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception error) {
            LogManager.debug("检查Wi-Fi连接状态失败: " + error.getClass().getSimpleName());
        }
        if (!enabled || !wifiActive || !isFTPServiceRunning()) {
            releaseWifiLock();
            return;
        }
        try {
            if (wifiLock == null) {
                WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (manager == null) return;
                wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FTP Server G2:wifi_lock");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) {
                wifiLock.acquire();
                LogManager.info("已保持 Wi-Fi 连接");
            }
        } catch (Exception error) {
            LogManager.error("获取 Wi-Fi 锁失败", error);
        }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Exception error) {
            LogManager.error("释放 Wi-Fi 锁失败", error);
        } finally {
            wifiLock = null;
        }
    }

    private void updateMdnsRegistration() {
        if (discoveryManager == null) return;
        boolean enabled = getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PreferenceConsts.MDNS_ENABLED, Constants.PreferenceConsts.MDNS_ENABLED_DEFAULT);
        if (!enabled || !isFTPServiceRunning()) {
            discoveryManager.unregister();
            return;
        }
        int port = getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getInt(Constants.PreferenceConsts.PORT_NUMBER, Constants.PreferenceConsts.PORT_NUMBER_DEFAULT);
        boolean ftps = getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PreferenceConsts.FTPS_ENABLED, false);
        discoveryManager.register(port, getString(R.string.app_name), ftps);
    }

}
