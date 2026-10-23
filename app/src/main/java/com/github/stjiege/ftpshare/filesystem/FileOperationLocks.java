package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Nonblocking path leases, shared by moves and destructive FTP operations. */
final class FileOperationLocks implements AutoCloseable {
    private static final List<FileOperationLocks> ACTIVE = new ArrayList<>();
    private final String[] paths;
    private FileOperationLocks(String[] paths) { this.paths = paths; }
    static FileOperationLocks acquire(File... files) throws IOException {
        String[] paths = new String[files.length];
        for (int i = 0; i < files.length; i++) paths[i] = files[i].getCanonicalPath();
        synchronized (ACTIVE) {
            for (FileOperationLocks held : ACTIVE) {
                for (String a : held.paths) for (String b : paths) {
                    if (a.equals(b) || a.startsWith(b + File.separator)
                            || b.startsWith(a + File.separator)) return null;
                }
            }
            FileOperationLocks lease = new FileOperationLocks(paths);
            ACTIVE.add(lease);
            return lease;
        }
    }
    @Override public void close() { synchronized (ACTIVE) { ACTIVE.remove(this); } }
}
