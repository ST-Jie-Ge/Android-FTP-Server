package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FtpFile;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 移动语义的回归测试：所有账户共用 {@link MoveSupport}，所以这里的断言对匿名、具名、
 * 完整存储三类账户同时成立。
 *
 * <p>本机单测跑在 JVM 上，{@code libsafe_move.so} 加载不到（{@code UnsatisfiedLinkError}），
 * 因此重命名这一步统一注入 {@link SafeMove.Operations} 桩；事务本身由 {@link SafeMoveTest}
 * 覆盖，这里覆盖的是「谁有资格移动」与「代理免疫」。</p>
 */
public class MoveSupportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @After public void reset() {
        MoveCancellation.clear();
        MoveSupport.setOperationsForTest(null);
    }

    private File file(String name, String text) throws IOException {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), text.getBytes("UTF-8"));
        return file;
    }

    private String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }

    /** 跨卷桩：第一次 rename 回 EXDEV，之后按真实语义落盘，目标已存在则回 EEXIST。 */
    private final class Ops implements SafeMove.Operations {
        int renames;
        boolean delete = true;
        @Override public int rename(File a, File b) throws IOException {
            if (++renames == 1) return SafeMove.CROSS_DEVICE;
            if (b.exists()) return 17;
            Files.move(a.toPath(), b.toPath());
            return 0;
        }
        @Override public long copy(File a, File b) throws IOException {
            Files.copy(a.toPath(), b.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return a.length();
        }
        @Override public Object stamp(File a) throws IOException { return text(a); }
        @Override public boolean deleteSource(File a) { return delete && a.delete(); }
    }

    /** 最小可用的 FtpFile 桩：只有移动相关的几个方法有意义。 */
    private static final class Stub implements FtpFile {
        final File file;
        boolean writable = true;
        boolean removable = true;
        boolean readable = true;
        Stub(File file) { this.file = file; }
        @Override public String getAbsolutePath() { return file == null ? "/" : file.getAbsolutePath(); }
        @Override public String getName() { return file == null ? "/" : file.getName(); }
        @Override public boolean isHidden() { return false; }
        @Override public boolean isDirectory() { return file != null && file.isDirectory(); }
        @Override public boolean isFile() { return file != null && file.isFile(); }
        @Override public boolean doesExist() { return file != null && file.exists(); }
        @Override public boolean isReadable() { return readable; }
        @Override public boolean isWritable() { return writable; }
        @Override public boolean isRemovable() { return removable; }
        @Override public boolean delete() { return file != null && file.delete(); }
        @Override public boolean mkdir() { return file != null && file.mkdir(); }
        @Override public long getLastModified() { return file == null ? 0L : file.lastModified(); }
        @Override public boolean setLastModified(long time) { return false; }
        @Override public long getSize() { return file == null ? 0L : file.length(); }
        @Override public String getOwnerName() { return "user"; }
        @Override public String getGroupName() { return "group"; }
        @Override public int getLinkCount() { return 1; }
        @Override public Object getPhysicalFile() { return file; }
        @Override public List<? extends FtpFile> listFiles() { return Collections.emptyList(); }
        @Override public InputStream createInputStream(long offset) { throw new UnsupportedOperationException(); }
        @Override public OutputStream createOutputStream(long offset) { throw new UnsupportedOperationException(); }
        @Override public boolean move(FtpFile destination) { return MoveSupport.move(this, destination); }
    }

    private static FtpFile proxy(FtpFile target) {
        return (FtpFile) Proxy.newProxyInstance(MoveSupportTest.class.getClassLoader(),
                new Class<?>[]{FtpFile.class}, (p, method, args) -> method.invoke(target, args));
    }

    @Test public void crossVolumeMoveCopiesThenDeletesSource() throws Exception {
        File source = file("source", "payload");
        Stub from = new Stub(source);
        Stub to = new Stub(new File(folder.getRoot(), "target"));
        assertTrue(MoveSupport.move(from, to, new Ops()));
        assertFalse(source.exists());
        assertEquals("payload", text(new File(folder.getRoot(), "target")));
    }

    @Test public void proxyDestinationIsAccepted() throws Exception {
        // 复现 SafeUploadFileSystemFactory 的代理：上游实现在这里会 ClassCastException
        // （NativeFtpFile）或直接返回 false（MultiRootFtpFile 的 instanceof）。
        File source = file("source", "payload");
        Stub from = new Stub(source);
        Stub to = new Stub(new File(folder.getRoot(), "target"));
        assertTrue(MoveSupport.move(proxy(from), proxy(to), new Ops()));
        assertFalse(source.exists());
    }

    @Test public void readOnlyDestinationIsRejected() throws Exception {
        File source = file("source", "payload");
        Stub from = new Stub(source);
        Stub to = new Stub(new File(folder.getRoot(), "target"));
        to.writable = false;
        assertFalse(MoveSupport.move(from, to, new Ops()));
        assertTrue(source.exists());
        assertFalse(new File(folder.getRoot(), "target").exists());
    }

    @Test public void nonRemovableSourceIsRejected() throws Exception {
        File source = file("source", "payload");
        Stub from = new Stub(source);
        from.removable = false;
        Stub to = new Stub(new File(folder.getRoot(), "target"));
        assertFalse(MoveSupport.move(from, to, new Ops()));
        assertTrue(source.exists());
    }

    @Test public void directoryIsNotCopiedAcrossVolumes() throws Exception {
        File source = folder.newFolder("dir");
        Stub from = new Stub(source);
        Stub to = new Stub(new File(folder.getRoot(), "moved"));
        assertFalse(MoveSupport.move(from, to, new Ops()));
        assertTrue(source.isDirectory());
        assertFalse(new File(folder.getRoot(), "moved").exists());
    }

    @Test public void failureNeverLeaksAMovePermit() throws Exception {
        int permits = 0;
        while (MoveSlots.acquire()) permits++;
        assertEquals(CopyExecutor.threadCount(), permits);
        while (permits > 0) { MoveSlots.release(); permits--; }

        for (int i = 0; i < CopyExecutor.threadCount() * 3; i++) {
            Stub from = new Stub(file("s" + i, "payload"));
            from.removable = false;
            assertFalse(MoveSupport.move(from, new Stub(new File(folder.getRoot(), "t" + i)), new Ops()));
        }
        assertTrue("失败路径也必须归还移动额度", MoveSlots.acquire());
        MoveSlots.release();
    }

    @Test public void missingPhysicalFileIsRejected() throws Exception {
        Stub from = new Stub(null);
        Stub to = new Stub(new File(folder.getRoot(), "target"));
        assertFalse(MoveSupport.move(from, to, new Ops()));
        assertFalse(MoveSupport.move(new Stub(file("s", "x")), new Stub(null), new Ops()));
    }

    @Test public void wrappedFileUsesTheSharedMovePath() throws Exception {
        // SafeMoveFileSystemFactory 包出来的文件，走的就是注入到 MoveSupport 的同一份语义：
        // 匿名 / 具名 / 完整存储三种账户在这里没有任何区别。
        MoveSupport.setOperationsForTest(new Ops());
        File source = file("source", "payload");
        File target = new File(folder.getRoot(), "target");
        FtpFile from = new SafeMoveFileSystemFactory.SafeMoveFtpFile(new Stub(source));
        FtpFile to = new SafeMoveFileSystemFactory.SafeMoveFtpFile(new Stub(target));
        assertTrue(from.move(proxy(to)));
        assertFalse(source.exists());
        assertEquals("payload", text(target));
    }
}
