package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 让<b>每一个</b>账户的每一个 {@code FtpFile} 都用上统一的移动语义。
 *
 * <p>背景：{@code MultiRootFileSystemFactory} 只对开了「完整存储」的具名账户生效，匿名账户和
 * 其余具名账户拿到的仍是上游 {@code NativeFtpFile}，它的 {@code move()} 是一句裸
 * {@code File.renameTo()}：跨卷（EXDEV）必失败，也没有 {@link SafeMove} 事务、路径租约、
 * 并发额度和 ABOR 取消钩子。同一个「重命名」动作因此在两类账户上有两种能力、两种失败模式
 * ——这正是"重命名失败 / 跨卷传输失败"反复出现的地方。装上这一层之后，匿名、具名、完整
 * 存储三者的移动路径完全同一份代码。</p>
 *
 * <p>只重写 {@code move()}。删除、建目录、写出流<b>不加锁</b>：{@link MultiRootFtpFile} 自己
 * 已经持有租约，外层再抢一次会让内部 {@code acquire} 返回 null，反而把这些操作全部判成
 * 「文件忙」而失败。上传的防截断由 {@link SafeUploadFileSystemFactory} 统一负责，与本层无关。</p>
 */
public final class SafeMoveFileSystemFactory implements FileSystemFactory {
    private final FileSystemFactory delegate;

    public SafeMoveFileSystemFactory(FileSystemFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public FileSystemView createFileSystemView(User user) throws FtpException {
        FileSystemView source = delegate.createFileSystemView(user);
        return source == null ? null : new View(source);
    }

    private static FtpFile wrap(FtpFile file) {
        return file == null || file instanceof SafeMoveFtpFile ? file : new SafeMoveFtpFile(file);
    }

    private static List<? extends FtpFile> wrap(List<? extends FtpFile> files) {
        if (files == null) return null;
        List<FtpFile> wrapped = new ArrayList<>(files.size());
        for (FtpFile file : files) wrapped.add(wrap(file));
        return wrapped;
    }

    /** Wraps every file the view hands out, so no caller can bypass the shared move path. */
    private static final class View implements FileSystemView {
        private final FileSystemView delegate;

        View(FileSystemView delegate) {
            this.delegate = delegate;
        }

        @Override public FtpFile getHomeDirectory() throws FtpException { return wrap(delegate.getHomeDirectory()); }
        @Override public FtpFile getWorkingDirectory() throws FtpException { return wrap(delegate.getWorkingDirectory()); }
        @Override public FtpFile getFile(String file) throws FtpException { return wrap(delegate.getFile(file)); }
        @Override public boolean changeWorkingDirectory(String dir) throws FtpException {
            return delegate.changeWorkingDirectory(dir);
        }
        @Override public boolean isRandomAccessible() throws FtpException { return delegate.isRandomAccessible(); }
        @Override public void dispose() { delegate.dispose(); }
    }

    /**
     * 只把 {@code move} 换成 {@link MoveSupport}，其余原样转发。
     *
     * <p>为什么不能用 instanceof 判断目标类型：上游 {@code NativeFtpFile.move()} 首句就是
     * {@code ((NativeFtpFile) dest).file}，而 {@link SafeUploadFileSystemFactory} 会把视图返回的
     * 每个 FtpFile 换成 JDK 动态代理，强转直接抛 {@code ClassCastException}。{@link MoveSupport}
     * 只用 {@code getPhysicalFile()} 取真实文件，对代理与本层包装都免疫。</p>
     */
    static final class SafeMoveFtpFile implements FtpFile {
        private final FtpFile delegate;

        SafeMoveFtpFile(FtpFile delegate) {
            this.delegate = delegate;
        }

        @Override public boolean move(FtpFile destination) { return MoveSupport.move(this, destination); }

        @Override public String getAbsolutePath() { return delegate.getAbsolutePath(); }
        @Override public String getName() { return delegate.getName(); }
        @Override public boolean isHidden() { return delegate.isHidden(); }
        @Override public boolean isDirectory() { return delegate.isDirectory(); }
        @Override public boolean isFile() { return delegate.isFile(); }
        @Override public boolean doesExist() { return delegate.doesExist(); }
        @Override public boolean isReadable() { return delegate.isReadable(); }
        @Override public boolean isWritable() { return delegate.isWritable(); }
        @Override public boolean isRemovable() { return delegate.isRemovable(); }
        @Override public boolean delete() { return delegate.delete(); }
        @Override public boolean mkdir() { return delegate.mkdir(); }
        @Override public long getLastModified() { return delegate.getLastModified(); }
        @Override public boolean setLastModified(long time) { return delegate.setLastModified(time); }
        @Override public long getSize() { return delegate.getSize(); }
        @Override public String getOwnerName() { return delegate.getOwnerName(); }
        @Override public String getGroupName() { return delegate.getGroupName(); }
        @Override public int getLinkCount() { return delegate.getLinkCount(); }
        @Override public Object getPhysicalFile() { return delegate.getPhysicalFile(); }
        @Override public List<? extends FtpFile> listFiles() { return wrap(delegate.listFiles()); }
        @Override public InputStream createInputStream(long offset) throws IOException {
            return delegate.createInputStream(offset);
        }
        @Override public OutputStream createOutputStream(long offset) throws IOException {
            return delegate.createOutputStream(offset);
        }
        @Override public String toString() { return "safeMove(" + delegate + ")"; }
    }
}
