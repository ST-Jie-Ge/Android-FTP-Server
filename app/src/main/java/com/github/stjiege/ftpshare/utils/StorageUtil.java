package com.github.stjiege.ftpshare.utils;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StorageUtil {
    public static boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    public static void openAllFilesAccessSettings(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                context.startActivity(intent);
            } catch (Exception e) {
                context.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        }
    }

    /** Restores paths saved by older builds that lower-cased TF-card volume IDs. */
    public static String normalizeExistingPath(@NonNull Context context, String path) {
        if (path == null || path.length() == 0) return path;
        try {
            File direct = new File(path);
            if (direct.exists()) return direct.getCanonicalPath();
            File[] roots = context.getExternalFilesDirs(null);
            if (roots != null) {
                for (File appDir : roots) {
                    if (appDir == null) continue;
                    String root = appDir.getAbsolutePath();
                    int index = root.toLowerCase(Locale.US).indexOf("/android/data");
                    if (index <= 0) continue;
                    root = root.substring(0, index);
                    String lowerRoot = root.toLowerCase(Locale.US);
                    String lowerPath = path.toLowerCase(Locale.US);
                    if (lowerPath.equals(lowerRoot)) return root;
                    if (lowerPath.startsWith(lowerRoot + "/")) return root + path.substring(root.length());
                }
            }
        } catch (Exception error) {
            LogManager.debug("规范化存储路径失败: " + error.getClass().getSimpleName());
        }
        return path;
    }

    public static String getMainStoragePath() {
        try {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        } catch (Exception e) {
            LogManager.error("读取主存储目录失败", e);
        }
        return "";
    }

    /**
     * 默认的共享根目录：内置存储下的 Download。
     *
     * <p>匿名与具名共用同一个兜底——早先只有匿名有（具名路径为空时整个账户被静默跳过），
     * 同一个"目录没配好"在两类账户上有两种结果，排查时很难对上。</p>
     */
    public static String getDefaultSharePath() {
        String root = getMainStoragePath();
        if (root == null || root.length() == 0) return "Download";
        return new File(root, Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
    }

    /** Safe default for anonymous access: never expose the storage root. */
    public static String getDefaultAnonymousPath() {
        return getDefaultSharePath();
    }

    /**
     * 所有账户统一的 home 解析：空路径回退到默认共享目录，否则按卷名大小写迁移后原样返回。
     *
     * <p>注意：这里不再做「根目录 → Download」的迁移。那套迁移只服务于旧版本把存储根目录当作
     * 默认值的遗留数据，如今默认值早已是 Download，保留它反而会误伤「用户主动选择存储根目录」
     * 的合法场景——只要标志位在某个环节丢失，用户选的根目录就被静默替换成下载目录。</p>
     */
    public static String resolveHomeDirectory(@NonNull Context context, String configuredPath) {
        if (configuredPath == null || configuredPath.trim().isEmpty()) {
            return getDefaultSharePath();
        }
        return normalizeExistingPath(context, configuredPath);
    }

    @TargetApi(19)
    public static List<String> getAvailableStoragePaths(@NonNull Context context) {
        final ArrayList<String> arrayList = new ArrayList<>();
        try {
            String primary = new File(getMainStoragePath()).getCanonicalPath();
            if (new File(primary).exists()) arrayList.add(primary);
            File[] exFiles = context.getExternalFilesDirs(null);
            if (exFiles != null) {
                for (File file : exFiles) {
                    try {
                        String path = file.getAbsolutePath();
                        int appData = path.toLowerCase(Locale.US).indexOf("/android/data");
                        if (appData > 0) path = path.substring(0, appData);
                        File root = new File(path);
                        String canonical = root.getCanonicalPath();
                        if (root.exists() && !arrayList.contains(canonical)) {
                            arrayList.add(canonical);
                        }
                    } catch (Exception e) {
                        LogManager.warning("读取外置存储根目录失败: " + e.getClass().getSimpleName());
                    }
                }
            }
        } catch (Exception e) {
            LogManager.warning("枚举可用存储失败: " + e.getClass().getSimpleName());
        }
        return arrayList;
    }

    public static String getStorageDisplayName(@NonNull Context context, String path) {
        try {
            String primary = new File(getMainStoragePath()).getCanonicalPath();
            String current = new File(path).getCanonicalPath();
            if (current.equals(primary) || current.startsWith(primary + File.separator)) {
                return "内置 ROM";
            }
        } catch (Exception error) {
            LogManager.debug("识别存储卷类型失败: " + error.getClass().getSimpleName());
        }
        return "TF 卡 / 外置存储";
    }
}
