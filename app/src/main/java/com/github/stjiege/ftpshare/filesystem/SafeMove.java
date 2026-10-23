package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.IOException;

/** Transaction policy separated from Android syscalls for deterministic regression tests. */
final class SafeMove {
    static final int CROSS_DEVICE = 18; // Linux EXDEV
    interface Operations {
        int rename(File source, File target) throws IOException;
        long copy(File source, File target) throws IOException;
        Object stamp(File source) throws IOException;
        boolean deleteSource(File source);
    }

    static boolean move(File source, File target, Operations ops) throws IOException {
        MoveCancellation.check();
        int renamed = ops.rename(source, target);
        if (renamed == 0) return true;
        // EEXIST, permissions and unsupported no-replace must not trigger a copy fallback.
        if (renamed != CROSS_DEVICE || !source.isFile()) return false;
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory()) return false;
        Object before = ops.stamp(source);
        long length = source.length();
        File temporary = File.createTempFile(".ftp-moving-", ".tmp", parent);
        FileOperationLocks lease = FileOperationLocks.acquire(temporary);
        if (lease == null) return false;
        boolean committed = false;
        try {
            long copied = ops.copy(source, temporary);
            MoveCancellation.check();
            if (copied != length || temporary.length() != length
                    || !before.equals(ops.stamp(source))) return false;
            if (ops.rename(temporary, target) != 0) return false;
            committed = true;
            // Commit is the cancellation boundary. Once committed, finish source removal.
            // Never delete target to roll back: it may already be another task's file.
            // A changed/replaced source is retained as well.
            return before.equals(ops.stamp(source)) && ops.deleteSource(source);
        } finally {
            // Only our staging name is cleaned; no code here unlinks the destination.
            try { if (!committed) temporary.delete(); }
            finally { lease.close(); }
        }
    }
    private SafeMove() { }
}
