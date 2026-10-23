package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import org.apache.ftpserver.filesystem.nativefs.NativeFileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/**
 * The upload wrapper hands every command a proxy. Anything that casts an incoming FtpFile back to
 * its concrete class (upstream NativeFtpFile.move does exactly that) breaks on that proxy, so
 * RNFR/RNTO must see the real file object.
 */
public class SafeUploadFileSystemFactoryTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private FtpFile file(FileSystemView view, String name) throws Exception {
        FtpFile file = view.getFile(name);
        assertNotNull(name + " must resolve", file);
        return file;
    }

    @Test public void renameSeesTheRealFileObjectBehindTheProxy() throws Exception {
        File home = folder.newFolder("home");
        assertTrue(new File(home, "a.txt").createNewFile());
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        FileSystemFactory wrapped =
                new SafeUploadFileSystemFactory(new NativeFileSystemFactory());

        FileSystemView view = wrapped.createFileSystemView(user);
        FtpFile source = file(view, "a.txt");
        FtpFile target = file(view, "b.txt");

        assertTrue("move() must not fail because the argument is a proxy", source.move(target));
        assertFalse(new File(home, "a.txt").exists());
        assertTrue(new File(home, "b.txt").exists());
    }

    @Test public void uploadStillStagesAndReplacesExistingFiles() throws Exception {
        File home = folder.newFolder("home2");
        File existing = new File(home, "a.txt");
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write("old".getBytes("UTF-8"));
        }
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user);

        FtpFile file = file(view, "a.txt");
        // offset 0 over an existing file is what STOR/APPE use; it must go through the staging path.
        java.io.OutputStream out = file.createOutputStream(0L);
        out.write("new".getBytes("UTF-8"));
        out.close();
        assertEquals("new", read(existing));
    }

    /** A zero-byte overwrite that is committed must replace the target with an empty file. */
    @Test public void emptyOverwriteCommitsToZeroLengthFile() throws Exception {
        File home = folder.newFolder("home3");
        File existing = new File(home, "a.txt");
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write("old".getBytes("UTF-8"));
        }
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user);

        FtpFile file = file(view, "a.txt");
        SafeUploadFileSystemFactory.StagedUploadStream out =
                (SafeUploadFileSystemFactory.StagedUploadStream) file.createOutputStream(0L);
        // No bytes written at all, then an explicit commit: the empty upload wins.
        out.commit();
        assertEquals("", read(existing));
        assertEquals(0L, existing.length());
    }

    /** A rolled-back upload (zero bytes or not) must leave the original file untouched. */
    @Test public void rollbackPreservesTheOriginalFile() throws Exception {
        File home = folder.newFolder("home4");
        File existing = new File(home, "a.txt");
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write("old".getBytes("UTF-8"));
        }
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user);

        FtpFile file = file(view, "a.txt");
        SafeUploadFileSystemFactory.StagedUploadStream out =
                (SafeUploadFileSystemFactory.StagedUploadStream) file.createOutputStream(0L);
        out.write("partial".getBytes("UTF-8"));
        out.rollback();
        assertEquals("old", read(existing));
    }

    /** A close() without an explicit commit/rollback still commits, so APPE-to-empty never loses data. */
    @Test public void plainCloseStillCommits() throws Exception {
        File home = folder.newFolder("home5");
        File existing = new File(home, "a.txt");
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write("old".getBytes("UTF-8"));
        }
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user);

        FtpFile file = file(view, "a.txt");
        java.io.OutputStream out = file.createOutputStream(0L);
        out.write("new".getBytes("UTF-8"));
        out.close();
        assertEquals("new", read(existing));
    }

    /**
     * 列举结果必须不是 JDK 代理：一次 LIST/MLSD 会对每个条目问 8-12 次属性，逐个包成代理等于
     * 让整次列举走 {@code Method.invoke()} 反射（实测 220 条目约 3900 次）。
     *
     * <p>这条同时也是性能回退的防线——谁要是把"顺手包装一下"加回去，这里会立刻红。</p>
     */
    @Test public void listedEntriesAreNotProxies() throws Exception {
        File home = folder.newFolder("home");
        assertTrue(new File(home, "one.txt").createNewFile());
        assertTrue(new File(home, "two.txt").createNewFile());
        assertTrue(new File(home, "sub").mkdir());

        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user(home));
        List<? extends FtpFile> children = view.getFile("/").listFiles();
        assertTrue(children != null && children.size() == 3);
        for (FtpFile child : children) {
            assertFalse("listed entry must not be a JDK proxy: " + child.getName(),
                    java.lang.reflect.Proxy.isProxyClass(child.getClass()));
        }
        // 包装不是取消了，只是挪到了单文件那条路上——下面这条保证它还在。
        assertTrue("getFile() must still be wrapped",
                java.lang.reflect.Proxy.isProxyClass(view.getFile("one.txt").getClass()));
    }

    /**
     * 去掉列举包装不能顺带把上传保护弄丢：{@code createOutputStream(0)} 覆盖已有文件时，
     * 拿到的必须仍是 staged 流（走临时文件 + 显式提交），而不是直接截断原文件的裸流。
     */
    @Test public void overwriteStillGoesThroughStagedStream() throws Exception {
        File home = folder.newFolder("home");
        File existing = new File(home, "a.txt");
        assertTrue(existing.createNewFile());
        try (FileOutputStream out = new FileOutputStream(existing)) {
            out.write("old".getBytes("UTF-8"));
        }

        FileSystemView view = new SafeUploadFileSystemFactory(new NativeFileSystemFactory())
                .createFileSystemView(user(home));
        java.io.OutputStream out = view.getFile("a.txt").createOutputStream(0L);
        assertTrue("overwrite must be staged, not a direct truncate",
                out instanceof SafeUploadFileSystemFactory.StagedUploadStream);
        out.close();
    }

    private static User user(File home) {
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        return user;
    }

    private static String read(File file) throws Exception {
        byte[] buffer = new byte[(int) file.length()];
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            int read = in.read(buffer);
            return new String(buffer, 0, Math.max(read, 0), "UTF-8");
        }
    }
}
