package com.github.stjiege.ftpshare.workers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.github.stjiege.ftpshare.utils.LogManager;

/**
 * 后台定期维护：目前只做日志剪枝（数量/总量上限 + 30 天老化）。
 *
 * <p>刻意不在这里放联网任务（例如自动检查更新）：应用的数据出境是用户显式同意后才开启的，
 * 一个自己发起的周期性网络请求会把这条边界弄模糊，收益也不值得。
 * 以后要加别的维护项，就在这个 Worker 里继续加分支。</p>
 */
public class MaintenanceWorker extends Worker {

    public MaintenanceWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            int removed = LogManager.pruneSavedLogs(getApplicationContext());
            if (removed > 0) LogManager.debug("定期维护：清理过期日志文件 " + removed + " 个");
        } catch (Exception error) {
            // 维护任务失败不该重试轰炸，也不该影响主功能。
            LogManager.debug("定期维护失败: " + error.getClass().getSimpleName());
        }
        return Result.success();
    }
}
