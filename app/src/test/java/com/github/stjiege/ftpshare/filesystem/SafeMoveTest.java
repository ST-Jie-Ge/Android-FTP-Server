package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SafeMoveTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    @After public void clearCancellation() { MoveCancellation.clear(); }
    private File file(String name, String text) throws IOException {
        File file = new File(folder.getRoot(), name);
        Files.write(file.toPath(), text.getBytes("UTF-8"));
        return file;
    }
    private String text(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), "UTF-8");
    }
    private class Ops implements SafeMove.Operations {
        int renames;
        boolean delete = true;
        @Override public int rename(File a, File b) throws IOException {
            if (++renames == 1) return SafeMove.CROSS_DEVICE;
            if (b.exists()) return 17;
            Files.move(a.toPath(), b.toPath());
            return 0;
        }
        @Override public long copy(File a, File b) throws IOException {
            Files.copy(a.toPath(), b.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return a.length();
        }
        @Override public Object stamp(File a) throws IOException { return text(a); }
        @Override public boolean deleteSource(File a) { return delete && a.delete(); }
    }
    private void noStagingFiles() {
        assertEquals(0, folder.getRoot().listFiles((d, n) -> n.startsWith(".ftp-moving-")).length);
    }
    @Test public void successfulCrossVolumeMoveCommitsThenDeletesSource() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        assertTrue(SafeMove.move(source, target, new Ops()));
        assertFalse(source.exists()); assertEquals("payload", text(target)); noStagingFiles();
    }
    @Test public void targetCreatedDuringCopyIsNeverReplaced() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public long copy(File a, File b) throws IOException {
                long n = super.copy(a, b); file("target", "winner"); return n;
            }
        };
        assertFalse(SafeMove.move(source, target, ops));
        assertEquals("payload", text(source)); assertEquals("winner", text(target)); noStagingFiles();
    }
    @Test public void sourceDeleteFailureRetainsBothCopies() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops(); ops.delete = false;
        assertFalse(SafeMove.move(source, target, ops));
        assertEquals("payload", text(source)); assertEquals("payload", text(target)); noStagingFiles();
    }
    @Test public void failedDeleteNeverRollsBackSomeoneElsesTarget() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public boolean deleteSource(File a) {
                try { file("target", "other task"); } catch (IOException e) { throw new AssertionError(e); }
                return false;
            }
        };
        assertFalse(SafeMove.move(source, target, ops));
        assertEquals("other task", text(target)); assertTrue(source.exists());
    }
    @Test public void cancellationAfterCopyKeepsSourceAndCleansStaging() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        AtomicBoolean cancelled = new AtomicBoolean(); MoveCancellation.bind(cancelled::get);
        Ops ops = new Ops() {
            @Override public long copy(File a, File b) throws IOException {
                long n = super.copy(a, b); cancelled.set(true); return n;
            }
        };
        try { SafeMove.move(source, target, ops); fail("Expected cancellation"); }
        catch (InterruptedIOException expected) { }
        assertTrue(source.exists()); assertFalse(target.exists()); noStagingFiles();
    }
    @Test public void interruptedCopyKeepsSourceAndCleansStaging() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public long copy(File a, File b) throws IOException {
                throw new IOException("disk full");
            }
        };
        try { SafeMove.move(source, target, ops); fail("Expected I/O failure"); }
        catch (IOException expected) { }
        assertTrue(source.exists()); assertFalse(target.exists()); noStagingFiles();
    }
    @Test public void changedSourceIsRetainedAndNotCommitted() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public long copy(File a, File b) throws IOException {
                long n = super.copy(a, b); file("source", "changed"); return n;
            }
        };
        assertFalse(SafeMove.move(source, target, ops));
        assertEquals("changed", text(source)); assertFalse(target.exists()); noStagingFiles();
    }
    @Test public void unsupportedAtomicRenameDoesNotFallBackToUnsafeCopy() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public int rename(File a, File b) { return 38; }
            @Override public long copy(File a, File b) { throw new AssertionError("unsafe fallback"); }
        };
        assertFalse(SafeMove.move(source, target, ops));
        assertTrue(source.exists()); assertFalse(target.exists()); noStagingFiles();
    }
    @Test public void sameVolumeRenameDoesNotCopy() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        Ops ops = new Ops() {
            @Override public int rename(File a, File b) throws IOException {
                Files.move(a.toPath(), b.toPath()); return 0;
            }
            @Override public long copy(File a, File b) { throw new AssertionError("unneeded copy"); }
        };
        assertTrue(SafeMove.move(source, target, ops));
        assertEquals("payload", text(target)); assertFalse(source.exists());
    }
    @Test public void sourceAndAncestorOperationsAreRejectedWithoutWaiting() throws Exception {
        File source = file("source", "payload"), target = new File(folder.getRoot(), "target");
        try (FileOperationLocks lease = FileOperationLocks.acquire(source, target)) {
            assertNotNull(lease);
            assertNull(FileOperationLocks.acquire(source));
            assertNull(FileOperationLocks.acquire(target));
            assertNull(FileOperationLocks.acquire(folder.getRoot()));
            try (FileOperationLocks independent = FileOperationLocks.acquire(new File(folder.getRoot(), "other"))) {
                assertNotNull(independent);
            }
        }
        try (FileOperationLocks released = FileOperationLocks.acquire(source)) { assertNotNull(released); }
    }
    @Test public void movePermitsAreBoundedAndIndependentOfTransferPermits() throws Exception {
        int before = TransferSlots.snapshot().inUse;
        int held = 0;
        try {
            while (MoveSlots.acquire()) held++;
            assertEquals(CopyExecutor.threadCount(), held);
            assertEquals(before, TransferSlots.snapshot().inUse);
            TransferSlots.acquire();
            try { assertEquals(before + 1, TransferSlots.snapshot().inUse); }
            finally { TransferSlots.release(); }
        } finally { while (held-- > 0) MoveSlots.release(); }
        assertTrue(MoveSlots.acquire()); MoveSlots.release();
    }
}
