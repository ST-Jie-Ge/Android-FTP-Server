package com.github.stjiege.ftpshare.workers;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.LogManager;

/**
 * FTP 服务的兜底自愈任务：服务被系统回收后，在下一个周期窗口把它重新拉起来。
 *
 * <h3>它补的是哪一段</h3>
 * 现有的保活是「前台服务 + 无障碍心跳 + 可选 Shizuku 看门狗」，前两者都依赖进程还活着：
 * 进程被系统整体杀掉时心跳也一起没了，没人再把服务拉起来。WorkManager 的任务持久化在
 * 自己的数据库里，进程被杀、甚至重启后都还会被调度，所以它能覆盖这一段真空。
 *
 * <h3>为什么最多 15 分钟才生效</h3>
 * {@link androidx.work.PeriodicWorkRequest} 的最小周期就是 15 分钟，且实际执行时刻由
 * JobScheduler 决定，Doze 下还会再往后挪。所以它定位是<b>兜底</b>，不是实时心跳——
 * 想秒级恢复只能靠前台服务本身不被杀，那条路系统不开放给普通应用。
 *
 * <h3>为什么用 persisted 标志而不是服务状态</h3>
 * 见 {@link Constants.PreferenceConsts#FTP_EXPECTED_RUNNING}：进程被杀后静态状态归零，
 * 只看服务状态会把"用户手动停止"误判成"被系统杀掉"，然后自说自话地重启——那是 bug。
 */
public class FtpSelfHealingWorker extends Worker {

    public FtpSelfHealingWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        try {
            if (!isExpectedRunning(context)) return Result.success();
            if (FtpService.isFTPServiceRunning()) return Result.success();
            if (!canStartWithoutPrompting(context)) return Result.success();
            // 二次确认：判定和真正启动之间隔着几次磁盘读，用户完全可能在这个缝里点了停止。
            // 把它拉起来只需要一次调用，但把它从"用户刚关掉"的状态拉起来是很讨厌的体验。
            if (!isExpectedRunning(context)) return Result.success();
            try {
                FtpService.startService(context);
                LogManager.info("自愈：FTP 服务已不在运行，已重新发起启动");
            } catch (RuntimeException error) {
                // Android 12+ 禁止从后台启动前台服务（ForegroundServiceStartNotAllowedException）。
                // 豁免清单里有一条是「用户已关闭本应用的电池优化」——本应用本来就在引导用户
                // 打开它，所以在这类设备上自愈是能成的；没开的那部分设备会走到这里。
                // 这是预期内的失败：不弹通知（用户此刻多半不在看手机），等下一个周期再试。
                String reason = FtpService.isBatteryOptimizationIgnored(context)
                        ? "系统仍判定为后台启动"
                        : "未关闭电池优化，后台启动前台服务被禁止";
                LogManager.debug("自愈启动被拒绝(" + reason + "): " + error.getClass().getSimpleName());
            }
        } catch (Exception error) {
            LogManager.debug("自愈检查失败: " + error.getClass().getSimpleName());
        }
        return Result.success();
    }

    private static boolean isExpectedRunning(Context context) {
        return CommonUtils.getSettingSharedPreferences(context)
                .getBoolean(Constants.PreferenceConsts.FTP_EXPECTED_RUNNING,
                        Constants.PreferenceConsts.FTP_EXPECTED_RUNNING_DEFAULT);
    }

    /**
     * FtpService.startService 在缺权限时会向 Context 弹提示（Activity 弹 Snackbar，否则 Toast）。
     * 那是给前台用户看的；从后台任务里冒出来只会变成骚扰，所以这里先自己挡掉。
     */
    private static boolean canStartWithoutPrompting(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return true;
        return context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }
}
