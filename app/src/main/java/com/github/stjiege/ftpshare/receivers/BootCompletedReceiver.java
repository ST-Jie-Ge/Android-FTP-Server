package com.github.stjiege.ftpshare.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.CommonUtils;

public class BootCompletedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equalsIgnoreCase(intent.getAction())) {
            if (CommonUtils.getSettingSharedPreferences(context)
                    .getBoolean(Constants.PreferenceConsts.START_AFTER_BOOT, Constants.PreferenceConsts.START_AFTER_BOOT_DEFAULT)) {
                try {
                    FtpService.startService(context);
                } catch (RuntimeException ignored) {
                    // Android/OEM policy may reject boot-time foreground-service recovery.
                }
            }
        }

    }
}

