package com.github.stjiege.ftpshare;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.Log4jLogcatAppender;
import com.github.stjiege.ftpshare.utils.Log4jLogManagerAppender;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.AppAnalytics;
import com.github.stjiege.ftpshare.utils.UpdateChecker;
import com.github.stjiege.ftpshare.workers.WorkScheduler;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class MyApplication extends Application {

    public static final Handler handler = new Handler(Looper.getMainLooper());
    private static MyApplication myApplication;

    public static Context getGlobalBaseContext() {
        return myApplication.getApplicationContext();
    }

    @Override
    public void onCreate() {
        myApplication = this;
        super.onCreate();
        AppAnalytics.applyPreference(this);

        // Apache FtpServer/MINA log through SLF4J → log4j but ship without an appender, so
        // their diagnostics are discarded unless we install one.
        //
        // · Debug builds also get the logcat bridge, at DEBUG level, for live troubleshooting.
        // · Release builds only install the LogManager bridge and lower the root logger to WARN:
        //   there is no logcat sink to keep, and refusing DEBUG/INFO early skips constructing a
        //   LoggingEvent for every chatty library call on a MINA thread.
        Logger root = Logger.getRootLogger();
        if (BuildConfig.DEBUG) {
            if (root.getAppender("Logcat") == null) {
                Log4jLogcatAppender appender = new Log4jLogcatAppender();
                appender.setName("Logcat");
                root.addAppender(appender);
            }
        } else {
            root.setLevel(Level.WARN);
        }
        if (root.getAppender("LogManager") == null) {
            Log4jLogManagerAppender appender = new Log4jLogManagerAppender();
            appender.setName("LogManager");
            appender.setThreshold(Level.WARN);
            root.addAppender(appender);
        }

        // WorkManager 的任务持久化在自己的库里，进程重启后会按库里的记录恢复，所以升级到
        // 本版本后同步一次即可，不需要在别处再补注册。自愈任务是否注册由持久化标志决定。
        WorkScheduler.enqueueMaintenance(this);
        WorkScheduler.syncSelfHealing(this);

        // 启动时检查更新（Gitee 更新日志，境内服务非数据出境；静默失败不影响启动）。
        // 更新来源与设置页「检查更新」的浏览器跳转相互独立，后者保持不变。
        UpdateChecker.checkAsync(this);

        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                String message = "未捕获异常 thread=" + thread.getName();
                LogManager.error(message, error);
                LogManager.saveFatalReport(MyApplication.this, message, error);
                if (previousHandler != null) previousHandler.uncaughtException(thread, error);
            }
        });
    }
}
