package com.github.stjiege.ftpshare.filesystem;

import android.system.StructStat;

import com.github.stjiege.ftpshare.utils.LogManager;

import org.apache.ftpserver.ftplet.FtpFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;

/**
 * 所有账户共用的移动执行器。
 *
 * <p><b>为什么必须有统一的入口</b>：早期只有开了「完整存储」的账户走 {@link MultiRootFtpFile}
 * ——它有 {@link SafeMove} 事务、跨卷拷贝、路径租约和并发额度；匿名账户与未开完整存储的具名
 * 账户走的仍是上游 {@code NativeFtpFile.move()}，那是一句裸 {@code File.renameTo()}：
 * 跨卷（EXDEV）必失败，且失败时没有拷贝回退、没有租约、没有取消钩子。同一个「重命名」动作
 * 因此有两种完全不同的能力和失败模式，这正是"重命名失败"与"跨卷传输失败"的根源。
 * 现在两类视图的每一个 {@code FtpFile} 都由 {@link SafeMoveFileSystemFactory} 包一层，
 * 移动一律落到这里。</p>
 *
 * <p><b>只用接口方法，绝不对入参做 instanceof 或强转</b>：上游 {@code NativeFtpFile.move()}
 * 首句是 {@code ((NativeFtpFile) dest).file}，而 {@link SafeUploadFileSystemFactory} 会把视图
 * 返回的每个 {@code FtpFile} 换成 JDK 动态代理，强转直接抛 {@code ClassCastException}。
 * 这里通过 {@code getPhysicalFile()} 取真实文件，对代理天然免疫。</p>
 */
public final class MoveSupport {

    /** 单次 transferTo 的分片上限（部分内核限制单次长度）。 */
    private static final long MAX_TRANSFER_CHUNK = 64L * 1024 * 1024;
    /** 本地拷贝的缓冲大小：比网络传输块更大，因为这里没有 RTT 要掩盖。 */
    private static final int COPY_BUFFER_SIZE = 256 * 1024;

    /** 单测注入点：null 表示用真实系统调用。 */
    private static volatile SafeMove.Operations operationsOverride;

    static void setOperationsForTest(SafeMove.Operations operations) {
        operationsOverride = operations;
    }

    static boolean move(FtpFile source, FtpFile destination) {
        return move(source, destination, null);
    }

    /**
     * 新文件系统路径使用的移动入口。不再要求入参是 {@link FtpFile}，
     * 避免命令层与具体实现类耦合。
     */
    public static boolean move(FtpPath source, FtpPath destination) {
        if (source == null || destination == null) return false;
        java.io.File from = source.getPhysicalFile();
        java.io.File to = destination.getPhysicalFile();
        if (from == null || to == null) return false;
        if (!destination.isWritable() || !source.isReadable() || !source.isRemovable()) return false;
        if (!MoveSlots.acquire()) return false;
        MoveProgress.Token token = null;
        try (FileOperationLocks lease = FileOperationLocks.acquire(from, to)) {
            if (lease == null) return false;
            token = MoveProgress.start(progressLabel(from),
                    from.isDirectory() ? directorySize(from) : from.length());
            SafeMove.Operations ops = defaultOperations();
            return moveFileOrDirectory(from, to, ops, token);
        } catch (IOException | UnsatisfiedLinkError | RuntimeException error) {
            LogManager.warning("移动未完成，保留已有文件：" + source.getAbsolutePath());
            return false;
        } finally {
            MoveProgress.finish(token);
            MoveSlots.release();
        }
    }

    static boolean move(FtpFile source, FtpFile destination, SafeMove.Operations injected) {
        if (source == null || destination == null) return false;
        Object sourcePhysical = source.getPhysicalFile();
        Object targetPhysical = destination.getPhysicalFile();
        if (!(sourcePhysical instanceof File) || !(targetPhysical instanceof File)) return false;
        File from = (File) sourcePhysical;
        File to = (File) targetPhysical;
        // 顺序与上游 RNTO 一致：目标可写、源可读、源可移动。三者都是接口方法，
        // 由各自的 FtpFile 实现按自己的权限模型回答（匿名只读账户在这里就被挡下）。
        if (!destination.isWritable() || !source.isReadable() || !source.isRemovable()) return false;
        if (!MoveSlots.acquire()) return false;
        MoveProgress.Token token = null;
        try (FileOperationLocks lease = FileOperationLocks.acquire(from, to)) {
            if (lease == null) return false;
            token = MoveProgress.start(progressLabel(from),
                    from.isDirectory() ? directorySize(from) : from.length());
            SafeMove.Operations ops = injected != null ? injected
                    : (operationsOverride != null ? operationsOverride : defaultOperations());
            return moveFileOrDirectory(from, to, ops, token);
        } catch (IOException | UnsatisfiedLinkError | RuntimeException error) {
            // UnsatisfiedLinkError：libsafe_move.so 没加载上（宿主单测 / 裁剪失误的 ROM）。
            // 落到这里一律"什么都没改"，原文件仍在。
            LogManager.warning("移动未完成，保留已有文件：" + source.getName());
            return false;
        } finally {
            MoveProgress.finish(token);
            MoveSlots.release();
        }
    }

    private static String progressLabel(File file) {
        return "Moving " + file.getName();
    }

    private static long directorySize(File dir) throws IOException {
        MoveCancellation.check();
        long size = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            MoveCancellation.check();
            if (child.isDirectory()) {
                size += directorySize(child);
            } else {
                size += child.length();
            }
        }
        return size;
    }

    /**
     * 统一处理文件或目录：同卷走原子 rename，跨卷走 staged 拷贝。
     * 当目标是已存在的目录时，把源移进该目录（与 shell mv 语义一致）。
     */
    private static boolean moveFileOrDirectory(File from, File to, SafeMove.Operations ops,
                                               MoveProgress.Token token)
            throws IOException {
        MoveCancellation.check();
        if (from.isDirectory()) {
            return moveDirectory(from, to, ops, token);
        }
        // 文件目标是目录时，移进目录内部。
        File actualTo = to;
        if (to.exists() && to.isDirectory()) {
            actualTo = new File(to, from.getName());
        }
        return SafeMove.move(from, actualTo, ops);
    }

    /**
     * 目录移动：先尝试原子 rename；跨卷时先把整棵树拷贝到目标卷上的临时目录，
     * 再 rename 到最终位置，最后删除源树。失败时只保留已经提交的目标，不残留半成品。
     */
    private static boolean moveDirectory(File from, File to, SafeMove.Operations ops,
                                         MoveProgress.Token token)
            throws IOException {
        MoveCancellation.check();

        // 先按文件管理器的语义解析最终目标。若目标落在源目录内部，跨卷回退复制
        // 会把新生成的内容再次纳入递归遍历，可能持续复制直至耗尽存储空间。
        File actualTo = to;
        if (to.exists()) {
            if (to.isDirectory()) {
                actualTo = new File(to, from.getName());
            } else {
                return false;
            }
        }
        if (isSameOrDescendant(from, actualTo)) return false;

        int renamed = ops.rename(from, actualTo);
        if (renamed == 0) return true;
        if (renamed != SafeMove.CROSS_DEVICE) return false;

        File parent = actualTo.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) return false;
        if (actualTo.exists()) return false;

        // 临时目录放在目标卷上，这样最后的 rename 才是原子的。
        File temp = createTempDirectory(".ftp-moving-dir-", parent);
        boolean committed = false;
        try {
            java.util.concurrent.atomic.AtomicLong copied = new java.util.concurrent.atomic.AtomicLong();
            copyDirectoryTree(from, temp, ops, token, copied);
            if (ops.rename(temp, actualTo) != 0) return false;
            committed = true;
            deleteRecursive(from);
            return true;
        } finally {
            if (!committed) deleteRecursive(temp);
        }
    }

    /** Returns true when target resolves to source itself or anywhere below it. */
    private static boolean isSameOrDescendant(File source, File target) throws IOException {
        String sourcePath = source.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(sourcePath)
                || targetPath.startsWith(sourcePath.endsWith(File.separator)
                        ? sourcePath : sourcePath + File.separator);
    }

    private static File createTempDirectory(String prefix, File parent) throws IOException {
        File temp = File.createTempFile(prefix, ".tmp", parent);
        if (!temp.delete() || !temp.mkdir()) {
            throw new IOException("Cannot create temp directory: " + temp);
        }
        return temp;
    }

    /** 递归拷贝目录树，文件复用单文件跨卷拷贝逻辑。 */
    private static void copyDirectoryTree(File source, File target, SafeMove.Operations ops,
                                          MoveProgress.Token token,
                                          java.util.concurrent.atomic.AtomicLong copied)
            throws IOException {
        File[] children = source.listFiles();
        if (children == null) return;
        for (File child : children) {
            MoveCancellation.check();
            File dest = new File(target, child.getName());
            if (child.isDirectory()) {
                if (!dest.mkdirs()) {
                    throw new IOException("mkdirs failed: " + dest);
                }
                copyDirectoryTree(child, dest, ops, token, copied);
            } else {
                File destParent = dest.getParentFile();
                if (destParent != null && !destParent.exists() && !destParent.mkdirs()) {
                    throw new IOException("mkdirs failed: " + destParent);
                }
                long fileCopied = ops.copy(child, dest);
                if (fileCopied != child.length() || dest.length() != child.length()) {
                    throw new IOException("Copy incomplete: " + child);
                }
                copied.addAndGet(fileCopied);
                MoveProgress.update(token, copied.get());
            }
        }
    }

    /** 递归删除文件或目录（后序），用于清理源树或失败的临时目录。 */
    private static void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursive(child);
        }
        file.delete();
    }

    private static SafeMove.Operations defaultOperations() {
        return new SafeMove.Operations() {
            @Override public int rename(File from, File to) {
                int result = NativeMove.rename(from, to);
                if (result != 0 && result != SafeMove.CROSS_DEVICE) {
                    // 17 = EEXIST（目标已存在，RFC 语义下的正常拒绝）；其余是权限/只读文件系统
                    // /路径不存在这类真错误。留一行，现场排查重命名失败时才有第一现场。
                    LogManager.debug("移动的重命名步骤未成功 errno=" + result + " name=" + to.getName());
                }
                return result;
            }
            @Override public long copy(File from, File to) throws IOException {
                return copyAcrossVolumes(from, to);
            }
            @Override public Object stamp(File source) throws IOException {
                try {
                    StructStat stat = android.system.Os.lstat(source.getAbsolutePath());
                    return stat.st_dev + ":" + stat.st_ino + ":" + stat.st_size
                            + ":" + stat.st_mtime + ":" + stat.st_ctime;
                } catch (android.system.ErrnoException e) { throw new IOException(e); }
            }
            @Override public boolean deleteSource(File source) { return source.delete(); }
        };
    }

    /**
     * 拷贝文件内容，返回已写入的字节数。
     *
     * <p>优先走 {@code FileChannel.transferTo}：它让内核直接在两个文件描述符之间搬数据，
     * 少一次用户态中转，也省掉每 32 KB 一次的系统调用。部分内核会对单次调用设上限，所以
     * 这里按 64 MB 分片循环；万一 {@code transferTo} 完全不受支持（返回 0 或抛异常），
     * 退回到 256 KB 的缓冲流。</p>
     *
     * <p><b>为什么不把它分片并行</b>：文件搬运瓶颈在闪存的顺序读写带宽而非 CPU，多写线程
     * 只会把顺序写拆成随机写，在 TF 卡上反而更慢。真正该并行的粒度是「多个客户端的多次
     * 移动」，那是 {@link CopyExecutor} 负责的事。</p>
     */
    static long copyAcrossVolumes(File source, File target) throws IOException {
        Long transferred = null;
        try {
            transferred = transferWithChannel(source, target);
        } catch (IOException | RuntimeException unusable) {
            MoveCancellation.check();
            transferred = null;
        }
        if (transferred == null || transferred != source.length()) {
            MoveCancellation.check();
            transferred = streamCopy(source, target);
        }
        return transferred;
    }

    private static long transferWithChannel(File source, File target) throws IOException {
        try (FileChannel input = new FileInputStream(source).getChannel();
             FileChannel output = new FileOutputStream(target).getChannel()) {
            long transferred = 0L;
            long total = source.length();
            while (transferred < total) {
                MoveCancellation.check();
                long moved = input.transferTo(transferred,
                        Math.min(total - transferred, MAX_TRANSFER_CHUNK), output);
                if (moved <= 0L) return transferred;
                transferred += moved;
            }
            output.force(true);
            return transferred;
        }
    }

    private static long streamCopy(File source, File target) throws IOException {
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream raw = new FileOutputStream(target);
             OutputStream output = new BufferedOutputStream(raw)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            long copied = 0L;
            while ((count = input.read(buffer)) != -1) {
                MoveCancellation.check();
                output.write(buffer, 0, count);
                copied += count;
            }
            output.flush();
            raw.getFD().sync();
            return copied;
        }
    }

    private MoveSupport() { }
}
