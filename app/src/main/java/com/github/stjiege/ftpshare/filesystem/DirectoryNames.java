package com.github.stjiege.ftpshare.filesystem;

import android.os.Build;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One directory handle, one name at a time. No File.list()/listFiles() allocation. */
public abstract class DirectoryNames implements Closeable {
    public abstract String next() throws IOException;

    public static DirectoryNames open(File directory) throws IOException {
        // Keep API 26 classes isolated from the API 23-25 verifier. SDK 0 is the host JVM.
        if (Build.VERSION.SDK_INT == 0 || Build.VERSION.SDK_INT >= 26) return Nio.open(directory);
        try { return new Native(directory); }
        catch (LinkageError unavailable) { throw new IOException("Directory reader unavailable", unavailable); }
    }

    @android.annotation.TargetApi(26)
    private static final class Nio {
        static DirectoryNames open(File directory) throws IOException {
            java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                    java.nio.file.Files.newDirectoryStream(directory.toPath());
            try {
                java.util.Iterator<java.nio.file.Path> iterator = stream.iterator();
                return new DirectoryNames() {
                    @Override public String next() throws IOException {
                        try { return iterator.hasNext() ? iterator.next().getFileName().toString() : null; }
                        catch (java.nio.file.DirectoryIteratorException error) { throw error.getCause(); }
                    }
                    @Override public void close() throws IOException { stream.close(); }
                };
            } catch (RuntimeException error) {
                try { stream.close(); } catch (IOException closing) { error.addSuppressed(closing); }
                throw error;
            }
        }
    }

    private static final class Native extends DirectoryNames {
        static { System.loadLibrary("safe_move"); }
        private long handle;
        Native(File directory) throws IOException {
            handle = openDirectory(directory.getPath().getBytes(StandardCharsets.UTF_8));
        }
        @Override public synchronized String next() throws IOException {
            if (handle == 0) throw new IOException("Directory reader closed");
            byte[] name = readDirectory(handle);
            return name == null ? null : new String(name, StandardCharsets.UTF_8);
        }
        @Override public synchronized void close() throws IOException {
            long previous = handle;
            handle = 0;
            if (previous != 0) closeDirectory(previous);
        }
        private static native long openDirectory(byte[] path) throws IOException;
        private static native byte[] readDirectory(long handle) throws IOException;
        private static native void closeDirectory(long handle) throws IOException;
    }
}
