package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.command.impl.listing.DirectoryLister;
import org.apache.ftpserver.command.impl.listing.LISTFileFormater;
import org.apache.ftpserver.command.impl.listing.ListArgument;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;
import static org.junit.Assume.assumeTrue;

/**
 * 属性合并（P0-2）与逃逸判定快速路径（P0-3）的正确性。
 *
 * <p>本机 JVM 上没有 {@code android.system.Os} 的真身（android.jar 是桩），所以真机上走的那条
 * 快路径在这里永远走不到。这里的做法是<b>注入一个语义等价的假取数器</b>，从而在 JVM 上验证
 * 两件事：合并确实发生了（每条目只查一次），以及合并之后<b>输出与不开快路径时逐字节相同</b>。
 *
 * <p>后一条是本轮最重要的回归保证：这次改动的唯一目的是少做系统调用，任何可见输出的改变
 * 都算事故。</p>
 */
public class FileStatTest {

    private static final int FILES = 200;
    private static final int DIRS = 20;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** 静态注入会跨测试残留，必须每次清掉。 */
    @After public void clearInjectedSource() {
        FileStat.setSourceForTest(null);
    }

    private User user() {
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        return user;
    }

    private FileSystemView view(File root) throws Exception {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        roots.put("device", new File(root.getCanonicalPath()));
        return new MultiRootFileSystemView(user(), roots, false);
    }

    private File seed() throws Exception {
        // 无参 newFolder 每次给唯一名字：同一个测试里要建两份内容相同的目录做输出比对。
        File root = new File(folder.newFolder().getCanonicalPath());
        for (int i = 0; i < FILES; i++) assertTrue(new File(root, "file" + i + ".dat").createNewFile());
        for (int i = 0; i < DIRS; i++) assertTrue(new File(root, "dir" + i).mkdir());
        return root;
    }

    private String listing(File root) throws Exception {
        return new DirectoryLister().listFiles(
                new ListArgument("/device", null, new char[0]), view(root), new LISTFileFormater());
    }

    /**
     * 与 {@code File} 语义等价、但只被问一次的假取数器。
     *
     * <p>符号链接位必须是<b>真的</b>：本测试第一版在这里硬编码 false，结果一个真实的逃逸链接
     * 被放行——那条失败正好证明了免检路径的全部依据就是这一个比特。</p>
     */
    private static FileStat.Source mirrorOfFile() {
        return file -> {
            boolean link;
            try {
                link = Files.isSymbolicLink(file.toPath());
            } catch (RuntimeException undetectable) {
                link = true; // 判不出来就当作链接，走保守路径
            }
            return new FileStat.Info(file.exists(), file.isDirectory(), file.isFile(), link,
                    file.length(), file.lastModified());
        };
    }

    @Test public void everyEntryIsProbedExactlyOnce() throws Exception {
        File root = seed();
        int[] probes = {0};
        FileStat.Source mirror = mirrorOfFile();
        FileStat.setSourceForTest(file -> {
            probes[0]++;
            return mirror.probe(file);
        });

        String output = listing(root);
        assertTrue(output.contains("file0.dat"));

        // 正好每个列举条目一次——不多不少。改动前是每条目约 10 次 stat/access。
        // 注意不含目录本身：只有 listFiles() 产出的条目才允许缓存（见 MultiRootFtpFile.listingEntry），
        // 从 getFile() 拿到的实例不缓存，以防被 RNFR/RNTO 跨命令持有而读到陈旧快照。
        assertEquals(FILES + DIRS, probes[0]);
    }

    @Test public void listingIsByteIdenticalWithAndWithoutTheFastPath() throws Exception {
        String withoutFastPath = listing(seed());
        FileStat.setSourceForTest(mirrorOfFile());
        String withFastPath = listing(seed());
        assertEquals(withoutFastPath, withFastPath);
    }

    /**
     * 验证 {@code FileStat.Info} 的语义被如实反映到接口上：一个"指向不存在目标的软链接"
     * 必须表现为不存在、非目录、非文件、大小为 0。
     *
     * <p>这里必须走<b>列举条目</b>而不是 {@code getFile()}——只有前者启用缓存
     * （见 {@code MultiRootFtpFile.listingEntry}），注入的取数器才起作用。</p>
     */
    @Test public void brokenSymlinkIsReportedAsMissing() throws Exception {
        FileStat.setSourceForTest(file -> new FileStat.Info(false, false, false, true, 0L, 0L));
        List<? extends FtpFile> children = view(seed()).getFile("/device").listFiles();
        assertTrue(children != null && !children.isEmpty());
        FtpFile file = children.get(0);
        assertFalse(file.doesExist());
        assertFalse(file.isDirectory());
        assertFalse(file.isFile());
        assertEquals(0L, file.getSize());
        assertEquals(0L, file.getLastModified());
    }

    /** {@code getFile()} 返回的实例会被 RNFR/RNTO 跨命令持有，必须完全不缓存。 */
    @Test public void instancesHeldAcrossCommandsNeverCache() throws Exception {
        int[] probes = {0};
        FileStat.setSourceForTest(file -> {
            probes[0]++;
            return new FileStat.Info(true, false, true, false, 1L, 2L);
        });
        FtpFile file = view(seed()).getFile("/device/file0.dat");
        for (int i = 0; i < 5; i++) {
            file.doesExist();
            file.isDirectory();
            file.isFile();
            file.getSize();
            file.getLastModified();
        }
        assertEquals("getFile() instances must not snapshot", 0, probes[0]);
    }

    @Test public void symlinkOutsideTheRootIsStillFilteredOut() throws Exception {
        File root = new File(folder.newFolder("root").getCanonicalPath());
        File outside = new File(folder.newFolder("outside").getCanonicalPath());
        assertTrue(new File(outside, "secret.dat").createNewFile());
        File link = new File(root, "escape");
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath());
        } catch (Exception | LinkageError unsupported) {
            assumeNoException("symbolic links are not creatable on this filesystem", unsupported);
        }

        // 逃逸过滤依赖 getCanonicalPath() 真的去解析符号链接。Windows 上 Java 不跟随符号链接
        // （实测 canonical 仍等于 link 自身），那样这个用例在本机无从验证——新旧实现都会列出它，
        // 属于平台差异而不是本次改动引入的回归。Linux/Android 上会真跑。
        assumeTrue("this JVM does not resolve symlinks in getCanonicalPath(), cannot verify here",
                link.getCanonicalPath().startsWith(outside.getCanonicalPath()));

        // 快速路径只对非符号链接生效；符号链接必须仍然走真实路径解析并被挡在根外。
        FileStat.setSourceForTest(mirrorOfFile());
        List<? extends FtpFile> children = view(root).getFile("/device").listFiles();
        assertTrue(children != null);
        for (FtpFile child : children) {
            assertFalse("symlink escape must not be listed: " + child.getName(),
                    "escape".equals(child.getName()));
        }
    }
}
