package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;
import android.content.SharedPreferences;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.utils.LogManager;
import com.github.stjiege.ftpshare.utils.StorageUtil;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手动停止 FTP 后清扫上传和跨卷移动遗留的临时文件与备份文件。
 *
 * <p>用户在主界面关闭 FTP 时触发：先等服务器在 {@code FtpService.onDestroy} 的
 * SERVER_EXECUTOR 任务里完全停掉（不再有活动会话），再递归扫所有账户可见的目录，
 * 删除 {@code .ftp-upload-*.tmp}、{@code .ftp-moving-*} 临时文件/目录，并统计
 * {@code *.ftp-bak} 备份件。跨卷移动池与服务器生命周期分离，因此清理前先排空移动槽，
 * 避免删除仍在使用的临时目标。</p>
 *
 * <p>清扫期间锁定 FTP 开关，防止新服务立即启动又把脏文件带进来或和清理冲突。
 * 锁的最短持续 3 秒（让用户感知），最长兜底 120 秒（防止目录极深时一直卡住）。</p>
 */
public final class UploadLeftoverSweep {

    private static final String TEMP_PREFIX = ".ftp-upload-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String MOVE_FILE_PREFIX = ".ftp-moving-";
    private static final String MOVE_DIRECTORY_PREFIX = ".ftp-moving-dir-";
    private static final String BACKUP_SUFFIX = ".ftp-bak";

    private static final long MIN_LOCK_MS = 3_000L;
    private static final long MAX_SWEEP_MS = 120_000L;

    private static final Object LOCK = new Object();
    private static final AtomicBoolean pending = new AtomicBoolean();
    private static final AtomicBoolean running = new AtomicBoolean();
    private static long lockUntil;

    /** FTP 开关是否因清理被锁定；startService 用此拒绝新的启动请求。 */
    public static boolean isStartLocked() {
        if (running.get()) return true;
        synchronized (LOCK) {
            if (System.currentTimeMillis() < lockUntil) return true;
            lockUntil = 0L;
        }
        return false;
    }

    /**
     * 用户手动停止 FTP 时调用。只设置标记和锁，不执行 IO——IO 必须发生在服务真正
     * 停止之后（见 {@link #runIfRequested}），否则可能删到活动上传的临时文件。
     */
    public static void request() {
        pending.set(true);
        synchronized (LOCK) {
            lockUntil = Math.max(lockUntil, System.currentTimeMillis() + MIN_LOCK_MS);
        }
    }

    /**
     * 在 {@code FtpService.onDestroy} 的 stop 任务末尾调用。服务已完全停止，可以安全
     * 扫目录；如果没有待处理的请求则直接返回（例如系统回收服务时不会触发）。
     */
    public static void runIfRequested(Context context) {
        if (!pending.compareAndSet(true, false)) return;
        if (!running.compareAndSet(false, true)) {
            // 已经有另一处正在清理（理论上不会发生，因为清理串在单线程 executor 上）。
            pending.set(false);
            return;
        }

        long startTime = System.currentTimeMillis();
        int tempCount = 0;
        int backupCount = 0;
        int errorCount = 0;
        boolean movesQuiesced = false;
        try {
            try {
                // Move workers use a process-wide executor and may outlive server.stop().
                // Only delete their staging paths after all move permits have drained.
                movesQuiesced = MoveSlots.acquireForSweep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!movesQuiesced) {
                LogManager.warning("跨卷移动仍在收尾，本次跳过 .ftp-moving-* 残留清理");
            }
            Set<File> roots = collectRoots(context);
            for (File root : roots) {
                if (root == null || !root.isDirectory()) continue;
                Result r = sweep(root, movesQuiesced);
                tempCount += r.temps;
                backupCount += r.backups;
                errorCount += r.errors;
                // 接近上限时停止继续扫：锁定兜底时长，避免目录极深时无限卡。
                if (System.currentTimeMillis() - startTime > MAX_SWEEP_MS - 5_000L) {
                    LogManager.warning("FTP 上传残留清理接近最大时长，已中止剩余目录");
                    break;
                }
            }
            StringBuilder msg = new StringBuilder("FTP 残留清理完成：删除 .ftp-upload-*.tmp / .ftp-moving-* ")
                    .append(tempCount).append(" 个，发现 *.ftp-bak ").append(backupCount).append(" 个");
            if (errorCount > 0) msg.append("，失败 ").append(errorCount).append(" 个");
            LogManager.info(msg.toString());
        } catch (Exception e) {
            LogManager.error("FTP 上传残留清理失败", e);
        } finally {
            if (movesQuiesced) MoveSlots.releaseAfterSweep();
            running.set(false);
            long elapsed = System.currentTimeMillis() - startTime;
            synchronized (LOCK) {
                // 即使扫得很快，也至少锁满最小可见时长。
                long remaining = MIN_LOCK_MS - elapsed;
                if (remaining > 0) {
                    lockUntil = Math.max(lockUntil, System.currentTimeMillis() + remaining);
                }
            }
        }
    }

    /** 收集需要扫的目录：匿名账户 home + 每个具名账户 home；完整存储账户额外扫全部卷。 */
    private static Set<File> collectRoots(Context context) {
        Set<File> roots = new LinkedHashSet<>();
        SharedPreferences settings = context.getSharedPreferences(
                Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);

        if (settings.getBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE,
                Constants.PreferenceConsts.ANONYMOUS_MODE_DEFAULT)) {
            String configured = settings.getString(
                    Constants.PreferenceConsts.ANONYMOUS_MODE_PATH,
                    Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_DEFAULT);
            addRoot(roots, StorageUtil.resolveHomeDirectory(context, configured));
        }

        for (AccountItem item : FtpService.getUserAccountList(context)) {
            String homeDirectory = StorageUtil.resolveHomeDirectory(context,
                    item.fullStorage ? StorageUtil.getMainStoragePath() : item.path);
            addRoot(roots, homeDirectory);
            if (item.fullStorage) {
                for (String vol : StorageUtil.getAvailableStoragePaths(context)) {
                    addRoot(roots, vol);
                }
            }
        }
        return roots;
    }

    private static void addRoot(Set<File> roots, String path) {
        if (path == null || path.isEmpty()) return;
        File dir = new File(path);
        if (!dir.isDirectory()) return;
        try {
            roots.add(dir.getCanonicalFile());
        } catch (IOException ignored) {
            roots.add(dir);
        }
    }

    /** 递归扫一个目录，删除临时文件、统计备份文件。限制深度避免极端目录结构栈溢出。 */
    private static Result sweep(File dir, boolean cleanupMoveTemps) {
        return sweep(dir, 0, cleanupMoveTemps);
    }

    private static Result sweep(File dir, int depth, boolean cleanupMoveTemps) {
        int temps = 0, backups = 0, errors = 0;
        if (depth > 64) {
            LogManager.warning("FTP 残留清理达到最大递归深度，跳过 " + dir);
            return new Result();
        }
        File[] files = dir.listFiles();
        if (files == null) return new Result();

        for (File file : files) {
            String name = file.getName();
            if (file.isDirectory() && name.startsWith(MOVE_DIRECTORY_PREFIX)) {
                if (cleanupMoveTemps) {
                    if (deleteTree(file)) temps++; else errors++;
                }
            } else if (file.isDirectory()) {
                Result sub = sweep(file, depth + 1, cleanupMoveTemps);
                temps += sub.temps;
                backups += sub.backups;
                errors += sub.errors;
            } else {
                boolean uploadTemp = name.startsWith(TEMP_PREFIX) && name.endsWith(TEMP_SUFFIX);
                boolean moveTemp = name.startsWith(MOVE_FILE_PREFIX) && name.endsWith(TEMP_SUFFIX);
                if ((uploadTemp || (cleanupMoveTemps && moveTemp))) {
                    if (file.delete()) {
                        temps++;
                    } else {
                        errors++;
                    }
                } else if (name.endsWith(BACKUP_SUFFIX)) {
                    backups++;
                }
            }
        }
        return new Result(temps, backups, errors);
    }

    private static boolean deleteTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) return false;
            for (File child : children) {
                if (!deleteTree(child)) return false;
            }
        }
        return file.delete();
    }

    private static final class Result {
        final int temps;
        final int backups;
        final int errors;

        Result() { this(0, 0, 0); }

        Result(int temps, int backups, int errors) {
            this.temps = temps;
            this.backups = backups;
            this.errors = errors;
        }
    }

    private UploadLeftoverSweep() {}
}
