package com.github.stjiege.ftpshare.utils;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import androidx.core.content.pm.PackageInfoCompat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 启动时的更新检查：从 Gitee 仓库读取更新日志（纯文本），比对 versionCode。
 *
 * <h3>数据来源</h3>
 * {@value UPDATE_URL} —— yydsdw/other1 仓库的 update.txt（Gitee 国内服务，非数据出境；
 * MaintenanceWorker「不放联网任务」的边界仍然成立——本检查只在这里、只在进程启动时执行一次）。
 *
 * <h3>协议</h3>
 * 文件头两行必须是 {@code versionCode=<int>} 与 {@code versionName=<string>}；
 * 其余行是给人看的更新日志，解析器忽略。raw 地址会 302 到 Gitee 的 CDN，
 * HttpURLConnection 默认跟随同协议重定向，无需特殊处理。
 *
 * <h3>结果与失败</h3>
 * 远端 versionCode 大于本机（PackageManager 的 longVersionCode）时，把远端
 * versionName 写进 SP；否则清除标记。无网/超时/解析失败一律静默——这是尽力而为
 * 的增强功能，不能影响启动。
 */
public final class UpdateChecker {
    public static final String UPDATE_URL =
            "https://gitee.com/yydsdw/other1/raw/master/update.txt";
    private static final String KEY_LATEST_NAME = "update_available_version_name";

    private UpdateChecker() { }

    /** Idempotent and silent; safe to call on every process start. */
    public static void checkAsync(Context context) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            try {
                String text = fetch(UPDATE_URL);
                int remoteCode = parseVersionCode(text);
                String remoteName = parseVersionName(text);
                if (remoteCode < 0 || TextUtils.isEmpty(remoteName)) return;
                PackageInfo info = app.getPackageManager()
                        .getPackageInfo(app.getPackageName(), 0);
                long localCode = PackageInfoCompat.getLongVersionCode(info);
                if (remoteCode > localCode) {
                    CommonUtils.getSettingSharedPreferences(app)
                            .edit().putString(KEY_LATEST_NAME, remoteName).apply();
                } else {
                    CommonUtils.getSettingSharedPreferences(app)
                            .edit().remove(KEY_LATEST_NAME).apply();
                }
            } catch (Exception ignored) {
                // 尽力而为：无网、超时、解析失败都保持现状，不影响启动。
            }
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Non-null when an update has been seen; value = latest versionName from the txt. */
    public static String getLatestVersionName(Context context) {
        return CommonUtils.getSettingSharedPreferences(context)
                .getString(KEY_LATEST_NAME, null);
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() != 200) return "";
            StringBuilder builder = new StringBuilder();
            // Reader 必须显式关闭：disconnect() 只释放连接的复用槽，不会关掉已经取出来的
            // 输入流，漏掉它会在每次启动检查时留下一条未关闭的连接。
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) builder.append(line).append('\n');
            }
            return builder.toString();
        } finally {
            connection.disconnect();
        }
    }

    static int parseVersionCode(String text) {
        String value = findValue(text, "versionCode");
        if (value == null) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    static String parseVersionName(String text) {
        return findValue(text, "versionName");
    }

    private static String findValue(String text, String key) {
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + "=")) return trimmed.substring(key.length() + 1).trim();
        }
        return null;
    }
}
