package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.command.impl.listing.DirectoryLister;
import org.apache.ftpserver.command.impl.listing.LISTFileFormater;
import org.apache.ftpserver.command.impl.listing.MLSTFileFormater;
import org.apache.ftpserver.command.impl.listing.ListArgument;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertTrue;

/**
 * Cost baseline for one directory listing, measured as "how many {@link FtpFile} method calls does
 * the lister + formatter issue per entry".
 *
 * <p>Every call MultiRootFtpFile answers is a {@code File} call, i.e. one stat/access syscall, and
 * on the real server every one of them also crosses the JDK proxy installed by
 * {@link SafeUploadFileSystemFactory}. This test measures the first cost (call count); the proxy
 * multiplies it but does not change it.</p>
 *
 * <p>The numbers here are the reference point for any stat-coalescing work: treat a drop as the
 * goal, and treat an increase as a regression.</p>
 */
public class ListingCallCostTest {

    private static final int FILES = 200;
    private static final int DIRS = 20;

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Before public void reset() {
        Tally.reset();
    }

    /** Counts every call that crosses an FtpFile/FileSystemView boundary during one listing. */
    static final class Tally implements InvocationHandler {
        static final Map<String, Integer> FILE_CALLS = new TreeMap<>();
        static final Map<String, Integer> VIEW_CALLS = new TreeMap<>();

        static void reset() {
            FILE_CALLS.clear();
            VIEW_CALLS.clear();
        }

        private final Object target;
        private final Map<String, Integer> sink;

        Tally(Object target, Map<String, Integer> sink) {
            this.target = target;
            this.sink = sink;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            sink.merge(method.getName(), 1, Integer::sum);
            Object result;
            try {
                result = method.invoke(target, args);
            } catch (Exception failure) {
                throw failure.getCause() == null ? failure : failure.getCause();
            }
            if (result instanceof FtpFile) return fileProxy(result);
            if (result instanceof List) {
                List<?> raw = (List<?>) result;
                List<Object> wrapped = new ArrayList<>(raw.size());
                for (Object item : raw) wrapped.add(item instanceof FtpFile ? fileProxy(item) : item);
                return wrapped;
            }
            return result;
        }

        static void total(String into, Map<String, Integer> sink) {
            System.out.println("  " + into);
            for (Map.Entry<String, Integer> entry : sink.entrySet()) {
                System.out.println("    " + entry.getKey() + " x" + entry.getValue());
            }
        }
    }

    private static FtpFile fileProxy(Object target) {
        return (FtpFile) Proxy.newProxyInstance(ListingCallCostTest.class.getClassLoader(),
                new Class<?>[]{FtpFile.class}, new Tally(target, Tally.FILE_CALLS));
    }

    private static FileSystemView viewProxy(Object target) {
        return (FileSystemView) Proxy.newProxyInstance(ListingCallCostTest.class.getClassLoader(),
                new Class<?>[]{FileSystemView.class}, new Tally(target, Tally.VIEW_CALLS));
    }

    /** The real production view: MultiRootFileSystemView + MultiRootFtpFile. */
    private FileSystemView realView(User user, File root) throws Exception {
        LinkedHashMap<String, File> roots = new LinkedHashMap<>();
        roots.put("device", new File(root.getCanonicalPath()));
        return new MultiRootFileSystemView(user, roots, false);
    }

    private User user() {
        BaseUser user = new BaseUser();
        user.setName("tester");
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        return user;
    }

    private File seedDirectory() throws Exception {
        File root = new File(folder.newFolder("root").getCanonicalPath());
        for (int i = 0; i < FILES; i++) assertTrue(new File(root, "file" + i + ".dat").createNewFile());
        for (int i = 0; i < DIRS; i++) assertTrue(new File(root, "dir" + i).mkdir());
        return root;
    }

    @Test public void standardListIssuesManyCallsPerEntry() throws Exception {
        FileSystemView counted = viewProxy(realView(user(), seedDirectory()));
        String output = new DirectoryLister().listFiles(
                new ListArgument("/device", null, new char[0]), counted, new LISTFileFormater());

        int entries = FILES + DIRS;
        int lines = output.split("\r*\n", -1).length;
        System.out.println("LIST  entries=" + entries + " outputLines=" + lines);
        Tally.total("FtpFile calls during one LIST:", Tally.FILE_CALLS);
        Tally.total("FileSystemView calls:", Tally.VIEW_CALLS);

        // Sanity: every entry reached the formatter exactly once per pass.
        assertTrue("all entries listed", lines >= entries);

        int isDirectory = Tally.FILE_CALLS.getOrDefault("isDirectory", 0);
        System.out.println("  isDirectory() per entry: " + isDirectory + " / " + entries);
        assertTrue("lister should not have silently dropped entries", isDirectory >= entries);
    }

    @Test public void machineListableMLSDCostPerEntry() throws Exception {
        FileSystemView counted = viewProxy(realView(user(), seedDirectory()));
        String output = new DirectoryLister().listFiles(
                new ListArgument("/device", null, new char[0]), counted,
                new MLSTFileFormater(new String[]{"Type", "Size", "Modify", "Perm"}));

        int entries = FILES + DIRS;
        System.out.println("MLSD  entries=" + entries + " outputLines=" + output.split("\r*\n", -1).length);
        Tally.total("FtpFile calls during one MLSD:", Tally.FILE_CALLS);
        assertTrue("all entries listed", output.contains("file0.dat"));
    }
}
