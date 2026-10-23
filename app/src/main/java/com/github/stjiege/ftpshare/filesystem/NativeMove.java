package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 原子不覆盖重命名，文件与目录都适用。
 *
 * <p>首选 {@code renameat2(RENAME_NOREPLACE)}：一次系统调用内完成「存在则失败」，没有窗口。
 * 但 Android 的 /sdcard 在 11+ 是 FUSE、之前是 sdcardfs，两者经常对这个 flag 回
 * ENOSYS/EINVAL/EOPNOTSUPP。此时 native 侧会降级（见 {@code app/src/main/cpp/safe_move.c}）：
 * 跨设备原样返回 EXDEV 交给 Java 的拷贝回退，同设备则用 {@code link()+unlink()}（同样原子
 * 不覆盖）或「存在性预检 + rename(2)」。<b>降级是必须的</b>——早期版本在这里失败关闭，
 * 一旦内核不支持，所有重命名（连带跨卷移动的提交步骤）全部失败，表现就是"重命名用不了"。</p>
 *
 * @return 0 成功；否则 errno（18 = EXDEV 跨卷，17 = EEXIST 目标已存在）
 */
final class NativeMove {
    static { System.loadLibrary("safe_move"); }
    static int rename(File source, File target) {
        String from = source.getAbsolutePath(), to = target.getAbsolutePath();
        if (from.indexOf('\0') >= 0 || to.indexOf('\0') >= 0) return 22;
        return renameNoReplace(from.getBytes(StandardCharsets.UTF_8), to.getBytes(StandardCharsets.UTF_8));
    }
    private static native int renameNoReplace(byte[] source, byte[] target);
}
