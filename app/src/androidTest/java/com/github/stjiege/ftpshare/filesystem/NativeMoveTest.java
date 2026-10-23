package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class NativeMoveTest {
    private File directory() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = File.createTempFile("move-test-", ".dir", context.getCacheDir());
        assertTrue(root.delete()); assertTrue(root.mkdir()); return root;
    }
    private File file(File root, String name) throws Exception {
        File file = new File(root, name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(7); }
        return file;
    }
    private void cleanup(File root) {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) child.delete();
        root.delete();
    }
    @Test public void existingFileAndDirectoryAreNeverOverwritten() throws Exception {
        File root = directory();
        try {
            File a = file(root, "a"), b = file(root, "b");
            assertNotEquals(0, NativeMove.rename(a, b));
            assertTrue(a.exists()); assertEquals(1, b.length());
            File dir = new File(root, "dir"); assertTrue(dir.mkdir());
            assertNotEquals(0, NativeMove.rename(a, dir));
            assertTrue(a.exists()); assertTrue(dir.isDirectory());
        } finally { cleanup(root); }
    }
    @Test public void simultaneousCommitHasExactlyOneWinner() throws Exception {
        File root = directory();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            File a = file(root, "a"), b = file(root, "b"), target = new File(root, "target");
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> first = workers.submit(() -> { start.await(); return NativeMove.rename(a, target); });
            Future<Integer> second = workers.submit(() -> { start.await(); return NativeMove.rename(b, target); });
            start.countDown();
            int x = first.get(5, TimeUnit.SECONDS), y = second.get(5, TimeUnit.SECONDS);
            assertTrue("Kernel must support RENAME_NOREPLACE for this test", (x == 0) != (y == 0));
            assertTrue(target.exists()); assertTrue(a.exists() != b.exists());
        } finally { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); cleanup(root); }
    }
    @Test public void supplementaryUnicodeAndDirectoryRenameWork() throws Exception {
        File root = directory();
        try {
            File source = file(root, "中文𠮷😀"), target = new File(root, "目标𠮷😀");
            assertEquals(0, NativeMove.rename(source, target));
            assertFalse(source.exists()); assertTrue(target.exists());
            File dir = new File(root, "dir"), moved = new File(root, "moved");
            assertTrue(dir.mkdir()); assertEquals(0, NativeMove.rename(dir, moved));
            assertTrue(moved.isDirectory());
        } finally { cleanup(root); }
    }
}
