package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 把临时文件原子替换为正式文件。同目录重命名在 Linux/Android 上本身原子；
 * Windows 桌面测试时 renameTo 拒绝覆盖已有目标，这里用 backup-rename 降级。
 */
final class FileCommits {

    static void commitFile(File temp, File target) throws IOException {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        if (temp.renameTo(target)) return;
        File backup = new File(target.getParentFile(), target.getName() + ".ftp-bak");
        if (backup.exists() && !backup.delete()) {
            throw new IOException("无法清理旧的备份文件 " + backup);
        }
        if (!target.renameTo(backup)) {
            throw new IOException("无法写入 " + target);
        }
        if (!temp.renameTo(target)) {
            if (!backup.renameTo(target)) {
                throw new IOException("无法写入 " + target + "，且还原失败，原文件保留在 " + backup);
            }
            throw new IOException("无法写入 " + target);
        }
        if (backup.exists() && !backup.delete()) {
            backup.deleteOnExit();
        }
    }

    private FileCommits() { }
}
