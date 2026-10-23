package com.github.stjiege.ftpshare.utils;

import android.content.Context;
import android.os.Bundle;

import com.github.stjiege.ftpshare.Constants;
import com.google.firebase.analytics.FirebaseAnalytics;
import com.google.firebase.crashlytics.FirebaseCrashlytics;

/**
 * Single privacy boundary for anonymous Firebase Analytics.
 * Never put account names, IP addresses, file names, paths, passwords, or log text in events.
 *
 * <p>Crashlytics（崩溃日志）与分析共用<b>同一个同意开关</b>：用户在弹窗同意后两者一起开、
 * 拒绝/关闭后一起停。manifest 里两个组件的 collection 默认都是 false，这里是唯一的
 * 开启点——不存在"分析关着但崩溃还在上报"的中间态。</p>
 */
public final class AppAnalytics {
    public static final String ANALYTICS_ENABLED = "firebase_analytics_enabled";
    private static final String ANALYTICS_PROMPT_SHOWN = "firebase_analytics_prompt_shown";

    private AppAnalytics() { }

    public static boolean isEnabled(Context context) {
        return CommonUtils.getSettingSharedPreferences(context)
                .getBoolean(ANALYTICS_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        CommonUtils.getSettingSharedPreferences(context).edit()
                .putBoolean(ANALYTICS_ENABLED, enabled).apply();
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled);
    }

    public static void applyPreference(Context context) {
        boolean enabled = isEnabled(context);
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(enabled);
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled);
    }

    /** The prompt is recorded before display, so activity recreation/backgrounding cannot show it again. */
    public static boolean claimFirstLaunchPrompt(Context context) {
        if (CommonUtils.getSettingSharedPreferences(context)
                .getBoolean(ANALYTICS_PROMPT_SHOWN, false)) {
            return false;
        }
        CommonUtils.getSettingSharedPreferences(context).edit()
                .putBoolean(ANALYTICS_PROMPT_SHOWN, true).apply();
        return true;
    }

    /** Emits only a fixed, anonymous product action. */
    public static void logServiceState(Context context, boolean running) {
        if (!isEnabled(context)) return;
        Bundle parameters = new Bundle();
        parameters.putString("state", running ? "running" : "stopped");
        FirebaseAnalytics.getInstance(context).logEvent("ftp_service_state", parameters);
    }
}
