package com.github.stjiege.ftpshare.filesystem;

import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.User;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Wraps the stock native view so an aborted STOR never truncates or partially corrupts an existing file.
 *
 * <p>Protection covers two write paths:
 * <ul>
 *   <li><b>覆盖上传</b>：{@code createOutputStream(0)} 在已存在文件上写入 temp，成功才
 *       原子替换原文件；失败/取消回滚。</li>
 *   <li><b>REST 续传</b>：{@code createOutputStream(offset>0)} 在 offset 不等于当前文件
 *       大小时，先把原文件 {@code [0, offset)} 前缀复制到 temp，再在 temp 中 offset 位置
 *       写入；成功才替换。jar 里 NativeFtpFile 会先 {@code setLength(offset)} 截断原文件，
 *       失败会丢尾部，这里通过 staged-resume 兜住。</li>
 * </ul>
 *
 * <p><b>The wrapper must hand the real file object to anything that takes one.</b>
 * Commands receive proxies from this class, and upstream code casts an incoming {@code FtpFile}
 * back to its implementation class — {@code NativeFtpFile.move()} opens with
 * {@code ((NativeFtpFile) dest).file}, and {@code MultiRootFtpFile.move()} starts with
 * {@code !(destination instanceof MultiRootFtpFile)}. Passed a proxy, the first throws
 * {@code ClassCastException} and the second returns {@code false}, so <b>RNFR/RNTO fails while
 * upload keeps working</b> — the upload path never casts. Every argument is therefore unwrapped
 * back to the object it hides before the call is delegated.</p>
 *
 * <h3>列举结果为什么不代理</h3>
 * 只有 {@code getFile()} / {@code getHomeDirectory()} / {@code getWorkingDirectory()} 的结果会
 * 被包成代理，{@code listFiles()} 返回的列表<b>原样返回</b>。理由：
 * <ul>
 *   <li><b>成本</b>：一次 LIST/MLSD 会对每个条目问 8-12 次属性（{@code isDirectory}、
 *       {@code getSize}、{@code getLastModified}…）。实测 220 个条目就是约 3900 次方法调用，
 *       逐个包成代理等于让这一次列举全部走 {@code Method.invoke()} 反射。</li>
 *   <li><b>不丢保护</b>：覆盖上传/REST 续传的防截断只由 {@code createOutputStream(offset)}
 *       触发，而会这么做的 STOR/APPE 拿文件对象走的是 {@code getFile()}——那条路仍然包装。经核对，jar 里只有
 *       LIST / NLST / MLSD / STAT 四个命令会调 {@code listFiles()}，<b>全是只读</b>，没有任何
 *       命令会对列举出来的条目调 {@code createOutputStream}。</li>
 *   <li><b>顺带修掉一个隐患</b>：列举结果现在是真实对象（内层 {@code SafeMoveFtpFile} 或
 *       {@code NativeFtpFile}），上游 {@code NativeFtpFile.move()} 那句
 *       {@code ((NativeFtpFile) dest).file} 强转不会再因为没有解包而炸。</li>
 * </ul>
 */
public final class SafeUploadFileSystemFactory implements FileSystemFactory {
    private final FileSystemFactory delegate;
    public SafeUploadFileSystemFactory(FileSystemFactory delegate) { this.delegate = delegate; }
    @Override public FileSystemView createFileSystemView(User user) throws FtpException {
        return view(delegate.createFileSystemView(user));
    }
    private static FileSystemView view(FileSystemView source) {
        return (FileSystemView) Proxy.newProxyInstance(SafeUploadFileSystemFactory.class.getClassLoader(),
                new Class<?>[]{FileSystemView.class}, (proxy, method, args) -> {
                    Object result = method.invoke(source, unwrapAll(args));
                    if (result instanceof FtpFile) return file((FtpFile) result);
                    if (result instanceof FileSystemView) return view((FileSystemView) result);
                    return result;
                });
    }
    private static FtpFile file(FtpFile source) {
        return (FtpFile) Proxy.newProxyInstance(SafeUploadFileSystemFactory.class.getClassLoader(),
                new Class<?>[]{FtpFile.class}, new FileHandler(source));
    }

    /** Remembers what it wraps, so a proxy handed back as an argument can be unwrapped again. */
    private static final class FileHandler implements InvocationHandler {
        private final FtpFile target;
        FileHandler(FtpFile target) { this.target = target; }
        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("createOutputStream".equals(method.getName()) && args != null
                    && args.length == 1) {
                long offset = ((Long) args[0]);
                OutputStream staged = stagedCreateOutputStream(target, offset);
                if (staged != null) return staged;
            }
            Object result;
            try { result = method.invoke(target, unwrapAll(args)); }
            catch (InvocationTargetException e) { throw e.getCause(); }
            // 只包装单个文件；列举结果（List）原样返回，理由见类注释里"列举结果为什么不代理"。
            return result instanceof FtpFile ? file((FtpFile) result) : result;
        }
    }

    /**
     * Replaces proxies created here with the file objects they hide. Nested wrappers are peeled
     * all the way down; anything else is passed through untouched.
     */
    private static Object[] unwrapAll(Object[] args) {
        if (args == null || args.length == 0) return args;
        Object[] unwrapped = null;
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (!(value instanceof FtpFile)) continue;
            Object real = unwrap(value);
            if (real == value) continue;
            if (unwrapped == null) unwrapped = args.clone();
            unwrapped[i] = real;
        }
        return unwrapped == null ? args : unwrapped;
    }

    private static Object unwrap(Object value) {
        Object current = value;
        while (current instanceof FtpFile && Proxy.isProxyClass(current.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(current);
            if (!(handler instanceof FileHandler)) break;
            current = ((FileHandler) handler).target;
        }
        return current;
    }

    /**
     * Decides whether a {@code createOutputStream(offset)} call should be staged.
     *
     * <ul>
     *   <li>offset == 0 on an existing file → overwrite staging；</li>
     *   <li>offset > 0 and offset != currentSize → REST resume that alters the file tail
     *       (truncate or zero-fill gap) → resume staging；</li>
     *   <li>offset > 0 and offset == currentSize → append at EOF, no truncation risk, plain path；</li>
     *   <li>new file at offset 0 → staged create, so cancellation leaves no partial file;</li>
     *   <li>non-plain target or unsupported missing-file resume → plain path.</li>
     * </ul>
     *
     * <p>The returned {@link StagedUploadStream} lets the command layer commit/rollback atomically.
     * Null means "use the stock stream".</p>
     */
    private static OutputStream stagedCreateOutputStream(FtpFile source, long offset) throws IOException {
        File target = (File) source.getPhysicalFile();
        if (target == null || (target.exists() && !target.isFile())) return null;
        if (!target.exists() && offset != 0L) return null;
        // APPE 的语义是追加，不是原子替换；走 staging 会让它在传输失败时把半截内容
        // 提交成正式文件（因为 APPE 不知道 staged 协议，只会 close）。APPE  offset 必为
        // 文件尾（由命令层保证），所以直接用底层流追加即可。
        if ("APPE".equals(CommandContext.get())) return null;
        File parent = target.getAbsoluteFile().getParentFile();
        File temp = File.createTempFile(".ftp-upload-", ".tmp", parent);
        boolean keepTemp = false;
        try {
            OutputStream output;
            if (offset == 0L) {
                output = new FileOutputStream(temp);
            } else {
                long currentSize = target.length();
                if (offset == currentSize) {
                    // Pure append, no truncation risk. Plain path keeps APPE fast and avoids a full copy.
                    return null;
                }
                output = stagedResumePrefix(target, temp, offset);
            }
            keepTemp = true;
            return new StagedUploadStream(output, temp, target);
        } finally {
            if (!keepTemp) temp.delete();
        }
    }

    /**
     * Copies {@code target[0, offset)} into {@code temp}, then positions the write pointer at
     * {@code offset}. If the source shrank during copy, the remainder is zero-filled to match
     * NativeFtpFile's {@code raf.setLength(offset)} semantics. The returned stream writes at
     * {@code offset} and takes ownership of the underlying {@link RandomAccessFile}.
     */
    private static OutputStream stagedResumePrefix(File target, File temp, long offset) throws IOException {
        RandomAccessFile outRaf = new RandomAccessFile(temp, "rw");
        try (RandomAccessFile in = new RandomAccessFile(target, "r")) {
            byte[] buf = new byte[64 * 1024];
            long remaining = offset;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                outRaf.write(buf, 0, n);
                remaining -= n;
            }
        }
        // If target shrunk under us, setLength extends with zeros exactly like NativeFtpFile
        // would for a REST offset beyond the current tail.
        outRaf.setLength(offset);
        outRaf.seek(offset);
        return new RafOutputStream(outRaf);
    }

    /**
     * A {@link FileOutputStream} that also closes the {@link RandomAccessFile} it came from.
     * Keeps the RAF alive as long as the stream is alive, preventing its finalizer from closing
     * the file descriptor while the upload is still writing.
     */
    private static final class RafOutputStream extends FileOutputStream {
        private final RandomAccessFile raf;
        RafOutputStream(RandomAccessFile raf) throws IOException {
            super(raf.getFD());
            this.raf = raf;
        }
        @Override public void close() throws IOException {
            try {
                super.close();
            } finally {
                try { raf.close(); } catch (IOException ignored) { }
            }
        }
    }

    /**
     * The stream returned for an overwrite upload. The upload command drives it:
     *
     * <ul>
     *   <li>transfer finished cleanly &amp; not cancelled → {@link #commit()}</li>
     *   <li>abort / read timeout / write failure / cancelled → {@link #rollback()}</li>
     * </ul>
     *
     * <p>{@link #close()} is the fallback for callers that do not know about the explicit
     * commit/rollback protocol (e.g. {@code APPE} appending to an empty file, which also lands on
     * {@code createOutputStream(0)}): it commits if neither {@code commit()} nor {@code rollback()}
     * has run yet. The command that <em>does</em> know (the migrated {@code STOR}) always decides
     * explicitly before {@code close()}, so the fallback never fires for it — which is what makes
     * "empty-file overwrite" correct: a zero-byte successful upload commits (target becomes empty),
     * while a zero-byte <em>cancelled</em> upload rolls back and preserves the original. The old
     * {@code count > 0} heuristic conflated those two cases.</p>
     */
    public static final class StagedUploadStream extends StagedOutputStream {
        private final File temp;
        private final File target;
        private boolean finished;

        StagedUploadStream(OutputStream output, File temp, File target) {
            super(output);
            this.temp = temp;
            this.target = target;
        }

        // 关键：不能走 super.write(b,o,l)。FilterOutputStream 的默认实现会循环逐字节调用
        // write(int)，底层 FileOutputStream 无缓冲，覆盖上传的整块数据会被拆成约 104 万次
        // 单字节写调用（1 MiB 数据）。这里直接转调 out.write(b,o,l) 走底层块写入。
        @Override public void write(int b) throws IOException { out.write(b); }
        @Override public void write(byte[] b, int o, int l) throws IOException { out.write(b, o, l); }

        /** Atomically replace the original with the uploaded temp file. */
        public synchronized void commit() throws IOException {
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

        /** Discard the temp file and leave the original untouched. */
        public synchronized void rollback() {
            if (finished) return;
            finished = true;
            try {
                out.close();
            } catch (IOException ignored) {
            }
            cleanup();
        }

        /**
         * Idempotent close. Commits if the outcome has not been decided yet, so that callers
         * unaware of the explicit protocol (e.g. {@code APPE} to an empty file) never lose data;
         * once {@code commit()}/{@code rollback()} has run this is a no-op.
         */
        @Override public synchronized void close() throws IOException {
            if (finished) return;
            commit();
        }

        private void cleanup() {
            if (temp.exists()) temp.delete();
        }
    }
}
