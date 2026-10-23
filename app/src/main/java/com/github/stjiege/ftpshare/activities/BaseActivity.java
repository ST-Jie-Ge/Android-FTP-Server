package com.github.stjiege.ftpshare.activities;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.utils.CommonUtils;

/**
 * Application language uses AppCompatDelegate's official per-app locale implementation.
 * The old preference is handed off once after Activity creation, as required by AppCompat.
 */
public abstract class BaseActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        android.content.SharedPreferences settings = CommonUtils.getSettingSharedPreferences(this);
        if (!settings.getBoolean(Constants.PreferenceConsts.LANGUAGE_MIGRATED, false)) {
            int legacy = settings.getInt(Constants.PreferenceConsts.LANGUAGE_SETTING,
                    Constants.PreferenceConsts.LANGUAGE_SETTING_DEFAULT);
            // Commit before setApplicationLocales recreates this Activity: a recreation can happen
            // immediately, and apply() would let the new instance repeat the migration.
            settings.edit().putBoolean(Constants.PreferenceConsts.LANGUAGE_MIGRATED, true).commit();
            LocaleListCompat locales = CommonUtils.localeListForSetting(legacy);
            // "Follow system" is already what an unset AppCompat locale list means, so handing
            // that over is a no-op — skip it and spare every upgrading user a pointless
            // recreate (the screen flash) on their first launch after the update.
            if (!locales.equals(AppCompatDelegate.getApplicationLocales())) {
                AppCompatDelegate.setApplicationLocales(locales);
            }
        }
    }
}
