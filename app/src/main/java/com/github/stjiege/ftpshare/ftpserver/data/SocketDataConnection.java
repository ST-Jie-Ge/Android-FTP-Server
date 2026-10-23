package com.github.stjiege.ftpshare.ftpserver.data;

import org.apache.ftpserver.impl.AsciiTransfer;
import org.apache.ftpserver.impl.DefaultFtpSession;
import org.apache.ftpserver.impl.FtpIoSession;

import com.github.stjiege.ftpshare.filesystem.TransferBuffer;
import com.github.stjiege.ftpshare.filesystem.TransferProgress;
import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.ftpserver.ftplet.DataConnection;
import org.apache.ftpserver.ftplet.DataType;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.util.IoUtils;
import org.apache.mina.core.session.IoSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class SocketDataConnection implements DataConnection {
    /**
     * TransferBuffer chooses the application read/write block size (64/256 KiB).
     * It is independent of TCP windows. Neither value guarantees maximum throughput;
     * benchmark the device/network/storage path before changing the defaults.
     */
    private final Logger LOG = LoggerFactory.getLogger(SocketDataConnection.class);
    private final FtpIoSession session;
    private final Socket socket;
    private com.github.stjiege.ftpshare.ftpserver.data.TimedSocketOutputStream timedOutput;
    private final DataConnectionManager factory;

    public SocketDataConnection(Socket socket, FtpIoSession session, DataConnectionManager factory) {
        this.session = session;
        this.socket = socket;
        this.factory = factory;
    }

    private InputStream getDataInputStream() throws IOException {
        try {
            Socket dataSoc = this.socket;
            if (dataSoc == null) {
                throw new IOException("Cannot open data connection.");
            } else {
                InputStream is = dataSoc.getInputStream();
                if (this.factory.isZipMode()) {
                    is = new InflaterInputStream((InputStream) is);
                }

                return (InputStream) is;
            }
        } catch (IOException var3) {
            this.factory.closeDataConnection();
            throw var3;
        }
    }

    private OutputStream getDataOutputStream() throws IOException {
        try {
            Socket dataSoc = this.socket;
            if (dataSoc == null) {
                throw new IOException("Cannot open data connection.");
            } else {
                timedOutput = new com.github.stjiege.ftpshare.ftpserver.data.TimedSocketOutputStream(dataSoc);
                OutputStream os = timedOutput;
                if (this.factory.isZipMode()) {
                    os = new DeflaterOutputStream((OutputStream) os);
                }

                return (OutputStream) os;
            }
        } catch (IOException | RuntimeException var3) {
            if (timedOutput != null) timedOutput.stopWatching();
            this.factory.closeDataConnection();
            throw var3;
        }
    }

    public final long transferFromClient(FtpSession session, OutputStream out) throws IOException {
        InputStream is = this.getDataInputStream();

        long var6;
        try {
            var6 = this.transfer(session, false, is, out);
        } finally {
            IoUtils.close(is);
        }

        return var6;
    }

    public final long transferToClient(FtpSession session, InputStream in) throws IOException {
        OutputStream out = this.getDataOutputStream();

        long var6;
        try {
            var6 = this.transfer(session, true, in, out);
        } finally {
            try { IoUtils.close(out); } finally {
                if (timedOutput != null) timedOutput.stopWatching();
            }
        }

        return var6;
    }

    public final void transferToClient(FtpSession session, String str) throws IOException {
        this.session.setTransferActive(true);
        try (OutputStream out = this.getDataOutputStream()) {
            // 一次编码，同时用于写盘与统计。原先先用 Writer 把字符串写一遍、再 getBytes()
            // 算一遍字节数：同一个字符串编码两次（LIST 一个大目录就是几千行），而且两处
            // 各自解析一次字符集名字。直接写字节还让统计值与实际上线的字节数必然一致。
            // 字符集取会话的：客户端用 OPTS UTF8 协商过就按 UTF-8 输出目录名，否则用全局设置。
            byte[] bytes = str.getBytes(session instanceof IoSession
                    ? FtpService.getSessionCharset((IoSession) session)
                    : FtpService.getActiveFtpCharset());
            out.write(bytes);
            if (session instanceof DefaultFtpSession) {
                ((DefaultFtpSession) session).increaseWrittenDataBytes(bytes.length);
            }
        } finally {
            if (timedOutput != null) timedOutput.stopWatching();
            this.session.setTransferActive(false);
        }
    }

    /**
     * Stream fallback for uploads, downloads, TLS, compression and ASCII conversion.
     * Output buffering coalesces short reads/conversion writes. TransferProgress keeps
     * byte accounting local and publishes at 1 MiB or 250 ms progress boundaries;
     * the final remainder is published on success and failure before clearing active.
     * These are notification thresholds, not network buffers or a rate limit.
     */
    private final long transfer(FtpSession session, boolean isWrite, InputStream in, OutputStream out) throws IOException {
        long transferredSize = 0L;
        // 块大小在本次传输内固定：中途换值会让 buff 长度与 bos 的内部缓冲对不上。
        final int bufferBytes = TransferBuffer.bytes();
        byte[] buff = new byte[bufferBytes];
        AsciiTransfer ascii = session.getDataType() == DataType.ASCII ? new AsciiTransfer(isWrite) : null;
        BufferedOutputStream bos = null;
        final DefaultFtpSession stats = session instanceof DefaultFtpSession ? (DefaultFtpSession) session : null;
        TransferProgress progress = new TransferProgress(bytes -> {
            if (stats != null) {
                if (isWrite) stats.increaseWrittenDataBytes(bytes);
                else stats.increaseReadDataBytes(bytes);
            }
            this.notifyObserver();
        });
        this.session.setTransferActive(true);

        try {
            bos = new BufferedOutputStream(out, bufferBytes);
            int count;
            while ((count = in.read(buff)) != -1) {
                long written;
                if (ascii == null) { bos.write(buff, 0, count); written = count; }
                else written = ascii.write(buff, count, bos);
                long wireBytes = isWrite ? written : count;
                transferredSize += wireBytes;
                progress.add((int) wireBytes);
            }

            if (ascii != null) {
                long tail = ascii.finish(bos);
                if (isWrite) {
                    transferredSize += tail;
                    progress.add((int) tail);
                }
            }
            // Closing flushes the final buffer (and finishes MODE Z). Any write/close
            // error must reach the command layer instead of becoming a false 226.
            bos.close();
            bos = null;
            return transferredSize;
        } catch (IOException var27) {
            this.LOG.warn("Exception during data transfer, closing data connection socket", var27);
            this.factory.closeDataConnection();
            throw var27;
        } catch (RuntimeException var28) {
            this.LOG.warn("Exception during data transfer, closing data connection socket", var28);
            this.factory.closeDataConnection();
            throw var28;
        } finally {
            // Always close the streams so their internal buffers (and the underlying socket)
            // are released promptly. Previously only flush() was called here, which left every
            // transfer holding an input buffer + output buffer until GC, causing heap
            // exhaustion and file-descriptor leaks that crashed the app during heavy I/O.
            IoUtils.close(in);
            IoUtils.close(bos);
            try { progress.flush(); } finally { this.session.setTransferActive(false); }
        }
    }

    protected void notifyObserver() {
        this.session.updateLastAccessTime();
    }
}
