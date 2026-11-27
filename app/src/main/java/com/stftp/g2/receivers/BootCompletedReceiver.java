package com.stftp.g2.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.stftp.g2.Constants;
import com.stftp.g2.services.FtpService;
import com.stftp.g2.utils.CommonUtils;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equalsIgnoreCase(intent.getAction())) {
            if (CommonUtils.getSettingSharedPreferences(context)
                    .getBoolean(Constants.PreferenceConsts.START_AFTER_BOOT, Constants.PreferenceConsts.START_AFTER_BOOT_DEFAULT)) {
                try {
                    if (!FtpService.startService(context)) com.stftp.g2.utils.LogManager.warning("Boot FTP start declined");
                } catch (RuntimeException error) {
                    com.stftp.g2.utils.LogManager.warning("Boot FTP start failed: " + error.getClass().getSimpleName());
                }
            }
        }

    }
}

