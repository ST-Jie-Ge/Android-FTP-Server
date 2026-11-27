package com.stftp.g2.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.stftp.g2.BuildConfig;
import com.stftp.g2.Constants;
import com.stftp.g2.services.FtpService;
import com.stftp.g2.utils.CommonUtils;
import com.stftp.g2.utils.LogManager;

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
    public static final String EXTRA_RESULT_STATUS = BuildConfig.APPLICATION_ID + ".extra.CONTROL_STATUS";
    public static final String EXTRA_RESULT_MESSAGE = BuildConfig.APPLICATION_ID + ".extra.CONTROL_MESSAGE";
    public static final int RESULT_REQUEST_REJECTED = 0;
    public static final int RESULT_REQUEST_ACCEPTED = 1;

    public static final String STATUS_ACCEPTED = "accepted";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_INTERFACE_DISABLED = "interface_disabled";
    public static final String STATUS_ATTACK_LOCKDOWN = "attack_lockdown";
    public static final String STATUS_UNKNOWN_ACTION = "unknown_action";
    public static final String STATUS_FAILED = "failed";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            respond(RESULT_REQUEST_REJECTED, STATUS_REJECTED, "Missing request intent.");
            return;
        }
        String action = intent.getAction();
        if (!ACTION_START_FTP.equals(action) && !ACTION_STOP_FTP.equals(action)) {
            respond(RESULT_REQUEST_REJECTED, STATUS_UNKNOWN_ACTION, "Unsupported action.");
            return;
        }
        if (!CommonUtils.getSettingSharedPreferences(context).getBoolean(
                Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED,
                Constants.PreferenceConsts.EXTERNAL_CONTROL_ENABLED_DEFAULT)) {
            LogManager.warning("External FTP control rejected: disabled");
            respond(RESULT_REQUEST_REJECTED, STATUS_INTERFACE_DISABLED, "External control is disabled in Laboratory.");
            return;
        }
        if (ACTION_START_FTP.equals(action) && CommonUtils.getSettingSharedPreferences(context).getBoolean(
                Constants.PreferenceConsts.FTP_ATTACK_LOCKDOWN, false)) {
            LogManager.warning("External FTP control rejected: security shutdown active");
            respond(RESULT_REQUEST_REJECTED, STATUS_ATTACK_LOCKDOWN, "FTP is locked down after suspected brute-force activity.");
            return;
        }
        if (ACTION_START_FTP.equals(action)) {
            try {
                boolean accepted = FtpService.startService(context.getApplicationContext());
                LogManager.info("External FTP start requested" + (accepted ? "" : "; request rejected"));
                if (accepted) {
                    respond(RESULT_REQUEST_ACCEPTED, STATUS_ACCEPTED, "Start request accepted; startup is asynchronous.");
                } else if (CommonUtils.getSettingSharedPreferences(context).getBoolean(
                        Constants.PreferenceConsts.FTP_ATTACK_LOCKDOWN, false)) {
                    respond(RESULT_REQUEST_REJECTED, STATUS_ATTACK_LOCKDOWN, "FTP is locked down after suspected brute-force activity.");
                } else {
                    respond(RESULT_REQUEST_REJECTED, STATUS_REJECTED, "FTP start request was not accepted.");
                }
            } catch (RuntimeException error) {
                LogManager.error("External FTP start failed", error);
                respond(RESULT_REQUEST_REJECTED, STATUS_FAILED, error.getClass().getSimpleName());
            }
        } else if (ACTION_STOP_FTP.equals(action)) {
            boolean accepted = FtpService.stopService();
            LogManager.info("External FTP stop requested" + (accepted ? "" : "; request failed"));
            respond(accepted ? RESULT_REQUEST_ACCEPTED : RESULT_REQUEST_REJECTED,
                    accepted ? STATUS_ACCEPTED : STATUS_FAILED,
                    accepted ? "Stop request accepted; shutdown is asynchronous." : "FTP stop request failed.");
        }
    }

    private void respond(int resultCode, String status, String message) {
        if (!isOrderedBroadcast()) {
            LogManager.warning("External FTP control: reply unavailable (unordered)");
            return;
        }
        Bundle result = new Bundle();
        result.putString(EXTRA_RESULT_STATUS, status);
        result.putString(EXTRA_RESULT_MESSAGE, message);
        setResultCode(resultCode);
        setResultExtras(result);
    }
}
