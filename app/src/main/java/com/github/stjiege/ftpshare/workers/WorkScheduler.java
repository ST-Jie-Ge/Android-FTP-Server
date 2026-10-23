package com.github.stjiege.ftpshare.workers;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.LogManager;

import java.util.concurrent.TimeUnit;

/**
 * WorkManager 任务的唯一调度入口。
 *
 * <h3>自愈任务是「跟着用户意图开关」的，不是常驻</h3>
 * 15 分钟的周期意味着系统每隔 15 分钟要把本应用进程拉起来一次——哪怕 FTP 正在跑、哪怕用户
 * 从来没开过 FTP。后台唤醒是要算在电量账上的，所以只有用户真的启动了 FTP 时才注册，
 * 手动停止后立刻撤销。任务本身持久化在 WorkManager 的库里，进程被杀、重启之后仍然会被
 * 调度，这正是它在的地方。
 *
 * <h3>维护任务是常驻的</h3>
 * 一天一次，且带「电量不低」约束，代价可以忽略。
 */
public final class WorkScheduler {

    private static final String WORK_SELF_HEALING = "ftp_self_healing";
    private static final String WORK_MAINTENANCE = "ftp_maintenance";

    private WorkScheduler() { }

    /** Enqueues or cancels the self-healing task so it matches the persisted user intent. */
    public static void syncSelfHealing(Context context) {
        try {
            WorkManager workManager = WorkManager.getInstance(context.getApplicationContext());
            boolean expected = CommonUtils.getSettingSharedPreferences(context)
                    .getBoolean(Constants.PreferenceConsts.FTP_EXPECTED_RUNNING,
                            Constants.PreferenceConsts.FTP_EXPECTED_RUNNING_DEFAULT);
            if (!expected) {
                workManager.cancelUniqueWork(WORK_SELF_HEALING);
                return;
            }
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    FtpSelfHealingWorker.class,
                    PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                    .build();
            // KEEP：服务已经在跑时重复调用不该把计时器归零，否则周期会被无限往后推。
            workManager.enqueueUniquePeriodicWork(WORK_SELF_HEALING,
                    ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception error) {
            // 调度失败只影响兜底能力，主功能不受影响，不能让它冒到调用方。
            LogManager.debug("同步自愈任务失败: " + error.getClass().getSimpleName());
        }
    }

    /** Idempotent: safe to call on every process start. */
    public static void enqueueMaintenance(Context context) {
        try {
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    MaintenanceWorker.class, 24L, TimeUnit.HOURS)
                    .setConstraints(new Constraints.Builder()
                            .setRequiresBatteryNotLow(true)
                            .build())
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniquePeriodicWork(WORK_MAINTENANCE,
                            ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Exception error) {
            LogManager.debug("注册定期维护任务失败: " + error.getClass().getSimpleName());
        }
    }
}
