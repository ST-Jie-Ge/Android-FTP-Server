package com.github.stjiege.ftpshare.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.github.stjiege.ftpshare.BuildConfig;
import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;
import com.github.stjiege.ftpshare.utils.LogManager;

/**
 * Opt-in integration endpoint for companion and automation apps.
 *
 * <p>Callers must declare {@value #PERMISSION_CONTROL_FTP}; the endpoint also rejects every
 * request until its owner enables external control in Laboratory. The receiver intentionally
 * accepts only fixed actions: it never accepts a shell command, path, account, or configuration
 * value from a third-party application.</p>
 */
public final class FtpControlReceiver extends BroadcastReceiver {
    public static final String PERMISSION_CONTROL_FTP = BuildConfig.APPLICATION_ID + ".permission.CONTROL_FTP";
    public static final String ACTION_START_FTP = BuildConfig.APPLICATION_ID + ".action.START_FTP";
    public static final String ACTION_STOP_FTP = BuildConfig.APPLICATION_ID + ".action.STOP_FTP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CommonUtils.getSettingSharedPreferences(context).getBoolean(
                Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED,
                Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED_DEFAULT)) {
            LogManager.warning("已拒绝外部 FTP 控制请求：接口未启用");
            return;
        }
        String action = intent.getAction();
        if (ACTION_START_FTP.equals(action)) {
            try {
                boolean accepted = FtpService.startService(context.getApplicationContext());
                LogManager.info("外部应用请求启动 FTP" + (accepted ? "" : "，请求未接受"));
            } catch (RuntimeException error) {
                LogManager.error("外部应用启动 FTP 失败", error);
            }
        } else if (ACTION_STOP_FTP.equals(action)) {
            FtpService.stopService();
            LogManager.info("外部应用请求停止 FTP");
        }
    }
}
