package com.github.stjiege.ftpshare.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.annotation.NonNull;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.R;
import com.google.android.material.snackbar.Snackbar;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class CommonUtils {

    /**
     * 拿到SP实例
     */
    public static SharedPreferences getSettingSharedPreferences(@NonNull Context context) {
        return context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 编码展示值
     *
     * @return GBK(简体中文)或者UTF-8(默认)
     */
    public static String getDisplayCharsetValue(@NonNull Context context) {
        return context.getResources().getString(
                Constants.Charset.CHAR_GBK.equals(getSettingSharedPreferences(context).getString(Constants.PreferenceConsts.CHARSET_TYPE, Constants.PreferenceConsts.CHARSET_TYPE_DEFAULT)) ?
                        R.string.item_charset_gbk : R.string.item_charset_utf
        );
    }

    /**
     * Ftp服务正在运行的SnackBar提示
     *
     * @param activity 要显示的activity
     */
    public static void showSnackBarOfFtpServiceIsRunning(@NonNull Activity activity) {
        try {
            Snackbar.make(activity.findViewById(android.R.id.content), activity.getResources().getString(R.string.attention_ftp_is_running), Snackbar.LENGTH_SHORT).show();
        } catch (Exception e) {
            LogManager.warning("显示FTP运行提示失败: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 向activity发送请求读写权限的snackBar
     */
    public static void showSnackBarOfRequestingWritingPermission(@NonNull final Activity activity) {
        Snackbar snackbar = Snackbar.make(activity.findViewById(android.R.id.content),
                activity.getResources().getString(R.string.permission_write_external),
                Snackbar.LENGTH_SHORT);
        snackbar.setAction(activity.getResources().getString(R.string.snackbar_action_goto), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", activity.getApplication().getPackageName(), null));
                activity.startActivity(intent);
            }
        });
        snackbar.show();
    }

    /**
     * 判断是否为匿名模式
     *
     * @return true-为匿名模式
     */
    public static boolean isAnonymousMode(@NonNull Context context) {
        return getSettingSharedPreferences(context).getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT);
    }

    /**
     * judge if it is a child path of parent
     */
    public static boolean isChildPathOfCertainPath(File child, File parent) {
        try {
            String childPath = child.getCanonicalPath().toLowerCase(java.util.Locale.US);
            String parentPath = parent.getCanonicalPath().toLowerCase(java.util.Locale.US);
            return childPath.equals(parentPath)
                    || childPath.startsWith(parentPath + File.separator);
        } catch (Exception e) {
            LogManager.warning("比较存储路径失败: " + e.getClass().getSimpleName());
        }
        return false;
    }

    private static String getIPAddressForFTPService(Context context) {
        if (NetworkEnvironmentUtil.isWifiConnected(context)) {
            final String wifiIp = NetworkEnvironmentUtil.getWifiIp(context);
            if (!TextUtils.isEmpty(wifiIp)) return wifiIp;
        }
        // 副标题的兜底 IP 固定取 IPv4：单行展示放不下 v6 地址，且默认监听就是 0.0.0.0。
        ArrayList<String> ips = NetworkEnvironmentUtil.getLocalIpAddresses(false);
        if (ips.size() > 0) return ips.get(0);
        return "127.0.0.1";
    }

    public static String getFTPServiceDisplayAddress(@NonNull Context context) {
        return getFtpServiceAddress(context, CommonUtils.getIPAddressForFTPService(context));
    }

    public static String getFtpServiceAddress(@NonNull Context context, @NonNull String ip) {
        String host = ip.contains(":") && !ip.startsWith("[") ? "[" + ip + "]" : ip;
        boolean ftps = context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getBoolean(Constants.PreferenceConsts.FTPS_ENABLED, false);
        return (ftps ? "ftps://" : "ftp://") + host + ":"
                + context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE)
                .getInt(Constants.PreferenceConsts.PORT_NUMBER, Constants.PreferenceConsts.PORT_NUMBER_DEFAULT);
    }
    public static int dip2px(Context context, float dpValue) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dpValue * scale + 0.5f);
    }

    /**
     * 获取本应用名称
     */
    public static @NonNull
    String getAppName(@NonNull Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(context.getPackageName(), 0);
            return String.valueOf(packageManager.getApplicationLabel(applicationInfo));
        } catch (Exception e) {
            LogManager.warning("读取应用名称失败: " + e.getClass().getSimpleName());
        }
        return "";
    }

    /**
     * 获取本应用版本名
     */
    public static @NonNull
    String getAppVersionName(@NonNull Context context) {
        try {
            PackageManager packageManager = context.getPackageManager();
            return String.valueOf(packageManager.getPackageInfo(context.getPackageName(), 0).versionName);
        } catch (Exception e) {
            LogManager.warning("读取应用版本失败: " + e.getClass().getSimpleName());
        }
        return "";
    }

    /**
     * 将秒数转换为按时分秒的格式的字符串
     */
    public static String getDisplayTimeOfSeconds(int seconds) {
        int hour = seconds / 3600;
        int minute = seconds % 3600 / 60;
        int second = seconds % 60;
        return getNumberWithZero(hour) + ":"
                + getNumberWithZero(minute) + ":"
                + getNumberWithZero(second);
    }

    /**
     * 将0~9之间的数值转换为00,01的数值字符串
     */
    private static String getNumberWithZero(int value) {
        if (value >= 0 && value < 10) {
            return "0" + value;
        }
        return String.valueOf(value);
    }


    public static String getDisplayTimeOfMillis(long millis) {
        Date date = new Date(millis);
        return date.toLocaleString();
    }

    /**
     * 计算出来的位置，y方向就在anchorView的上面和下面对齐显示，x方向就是与屏幕右边对齐显示
     * 如果anchorView的位置有变化，就可以适当自己额外加入偏移来修正
     *
     * @param anchorView  呼出window的view
     * @param contentView window的内容布局
     * @return window显示的左上角的xOff, yOff坐标
     */
    public static int[] calculatePopWindowPos(final View anchorView, final View contentView) {
        final int windowPos[] = new int[2];
        final int anchorLoc[] = new int[2];
        // 获取锚点View在屏幕上的左上角坐标位置
        anchorView.getLocationOnScreen(anchorLoc);
        final int anchorHeight = anchorView.getHeight();
        // 获取屏幕的高宽
        final int screenHeight = anchorView.getContext().getResources().getDisplayMetrics().heightPixels;//ScreenUtils.getScreenHeight(anchorView.getContext());
        final int screenWidth = anchorView.getResources().getDisplayMetrics().widthPixels;
        contentView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        // 计算contentView的高宽
        final int windowHeight = contentView.getMeasuredHeight();
        final int windowWidth = contentView.getMeasuredWidth();
        // 判断需要向上弹出还是向下弹出显示
        final boolean isNeedShowUp = (screenHeight - anchorLoc[1] - anchorHeight < windowHeight);
        if (isNeedShowUp) {
            windowPos[0] = screenWidth - windowWidth;
            windowPos[1] = anchorLoc[1] - windowHeight;
        } else {
            windowPos[0] = screenWidth - windowWidth;
            windowPos[1] = anchorLoc[1] + anchorHeight;
        }
        return windowPos;
    }

    public static boolean isALegalFileName(@NonNull String name) {
        try {
            if (name.trim().isEmpty()) return false;
            if (name.contains("?") || name.contains("\\") || name.contains("/") || name.contains(":") || name.contains("*") || name.contains("\"")
                    || name.contains("<") || name.contains(">") || name.contains("|")) return false;
        } catch (Exception e) {
            LogManager.warning("校验文件名失败: " + e.getClass().getSimpleName());
        }
        return true;
    }

    /**
     * 把「设置-语言」对话框的旧档位值映射为 AppCompat 应用内语言的 LocaleList。
     * 跟随系统 = 空列表（交还系统）；简体中文 = zh-CN（命中默认 values/ 的中文文案）；
     * 英文 = en。
     */
    public static androidx.core.os.LocaleListCompat localeListForSetting(int value) {
        if (value == Constants.PreferenceConsts.LANGUAGE_SIMPLIFIED_CHINESE)
            return androidx.core.os.LocaleListCompat.forLanguageTags("zh-CN");
        if (value == Constants.PreferenceConsts.LANGUAGE_ENGLISH)
            return androidx.core.os.LocaleListCompat.forLanguageTags("en");
        return androidx.core.os.LocaleListCompat.getEmptyLocaleList();
    }

    /**
     * 更新上下文的资源Resources(更新语言)
     */
    public static void updateResourcesOfContext(@NonNull Context context) {
        // 获得res资源对象
        Resources resources = context.getResources();
        // 获得屏幕参数：主要是分辨率，像素等。
        DisplayMetrics metrics = resources.getDisplayMetrics();
        // 获得配置对象
        Configuration config = resources.getConfiguration();
        //区别17版本（其实在17以上版本通过 config.locale设置也是有效的，不知道为什么还要区别）
        //在这里设置需要转换成的语言，也就是选择用哪个values目录下的strings.xml文件
        int value = CommonUtils.getSettingSharedPreferences(context).getInt(Constants.PreferenceConsts.LANGUAGE_SETTING, Constants.PreferenceConsts.LANGUAGE_FOLLOW_SYSTEM);
        Locale locale = null;
        switch (value) {
            default:
                break;
            case Constants.PreferenceConsts.LANGUAGE_FOLLOW_SYSTEM:
                locale = Locale.getDefault();
                break;
            case Constants.PreferenceConsts.LANGUAGE_SIMPLIFIED_CHINESE:
                locale = Locale.SIMPLIFIED_CHINESE;
                break;
            case Constants.PreferenceConsts.LANGUAGE_ENGLISH:
                locale = Locale.ENGLISH;
                break;
        }
        if (locale == null) return;
        // minSdk 23 > JELLY_BEAN_MR1(17)，旧分支恒不可达：config.locale 早已废弃。
        config.setLocale(locale);
        resources.updateConfiguration(config, metrics);
    }
}

