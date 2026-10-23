package com.github.stjiege.ftpshare.filesystem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * 本地 staged 上传输出流。
 *
 * <p>新建/覆盖上传（offset == 0）直接写 temp；REST 续传（offset > 0 且改变文件尾）先把
 * 原文件前缀复制到 temp。命令层通过 {@link #commit()} / {@link #rollback()} 控制结果。</p>
 */
final class LocalStagedOutputStream extends StagedOutputStream {

    private final File temp;
    private final File target;
    private boolean finished;

    static LocalStagedOutputStream overwrite(File target) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        File temp = File.createTempFile(".ftp-upload-", ".tmp", parent);
        try {
            return new LocalStagedOutputStream(new FileOutputStream(temp), temp, target);
        } catch (IOException | RuntimeException error) {
            temp.delete();
            throw error;
        }
    }

    static LocalStagedOutputStream resume(File target, long offset) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        File temp = File.createTempFile(".ftp-upload-", ".tmp", parent);
        RandomAccessFile outRaf = null;
        boolean handedOff = false;
        try {
            MoveCancellation.check();
            outRaf = new RandomAccessFile(temp, "rw");
            try (RandomAccessFile in = new RandomAccessFile(target, "r")) {
                byte[] buf = new byte[64 * 1024];
                long remaining = offset;
                while (remaining > 0) {
                    MoveCancellation.check();
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    outRaf.write(buf, 0, n);
                    remaining -= n;
                }
            }
            outRaf.setLength(offset);
            outRaf.seek(offset);
            MoveCancellation.check();
            LocalStagedOutputStream stream = new LocalStagedOutputStream(new RafOutputStream(outRaf), temp, target);
            handedOff = true;
            return stream;
        } finally {
            if (!handedOff) {
                try { if (outRaf != null) outRaf.close(); } finally { temp.delete(); }
            }
        }
    }

    private LocalStagedOutputStream(OutputStream output, File temp, File target) {
        super(output);
        this.temp = temp;
        this.target = target;
    }

    @Override public void write(int b) throws IOException { out.write(b); }
    @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }

    @Override public synchronized void commit() throws IOException {
        if (finished) return;
        finished = true;
        try {
            try (OutputStream closing = out) {
                closing.flush();
                if (closing instanceof FileOutputStream) ((FileOutputStream) closing).getFD().sync();
            }
            MoveCancellation.check();
            FileCommits.commitFile(temp, target);
        } finally {
            cleanup();
        }
    }

    @Override public synchronized void rollback() {
        if (finished) return;
        finished = true;
        try { out.close(); } catch (IOException ignored) { }
        cleanup();
    }

    @Override public synchronized void close() throws IOException {
        if (finished) return;
        commit();
    }

    private void cleanup() {
        if (temp.exists()) temp.delete();
    }

    /** 保持 RAF 在流存活期间不被 finalizer 关闭。 */
    private static final class RafOutputStream extends FileOutputStream {
        private final RandomAccessFile raf;
        RafOutputStream(RandomAccessFile raf) throws IOException {
            super(raf.getFD());
            this.raf = raf;
        }
        @Override public void close() throws IOException {
            try { super.close(); } finally { try { raf.close(); } catch (IOException ignored) { } }
        }
    }
}
