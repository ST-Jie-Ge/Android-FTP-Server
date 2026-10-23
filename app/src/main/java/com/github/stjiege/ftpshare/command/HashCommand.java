package com.github.stjiege.ftpshare.command;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import com.github.stjiege.ftpshare.filesystem.MoveCancellation;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

/**
 * HASH command (draft-bryan-ftpserver-hash) with SHA-256 support.
 *
 * <ul>
 *   <li>{@code HASH} with no argument → {@code 200 SHA-256} (current algorithm).</li>
 *   <li>{@code HASH <pathname>} → {@code 213 SHA-256 <hex-hash> <pathname>} for an
 *       existing, readable, regular file.</li>
 * </ul>
 *
 * <p>The command is registered by {@code FtpsCommandGuard} and routed to the transfer
 * executor by {@code DefaultFtpHandler}, so hashing a large file does not block MINA's
 * command I/O thread.</p>
 */
public final class HashCommand extends AbstractCommand {

    private static final String ALGORITHM = "SHA-256";
    private static final int REPLY_200 = 200;
    private static final int REPLY_213 = 213;
    private static final int REPLY_501 = 501;
    private static final int REPLY_550 = 550;
    private static final int REPLY_551 = 551;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    @Override
    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request)
            throws IOException, FtpException {

        String argument = request.getArgument();
        if (argument == null || argument.isEmpty()) {
            // RFC/draft: HASH with no argument returns the currently selected algorithm.
            session.write(new DefaultFtpReply(REPLY_200, ALGORITHM));
            return;
        }

        FtpFile file = null;
        try {
            file = session.getFileSystemView().getFile(argument);
        } catch (Exception ignored) {
            // leave file == null, reply 550 below
        }
        if (file == null || !file.doesExist() || !file.isFile()) {
            session.write(new DefaultFtpReply(REPLY_550, "File not found"));
            return;
        }
        if (!file.isReadable()) {
            session.write(new DefaultFtpReply(REPLY_550, "Permission denied"));
            return;
        }

        try (InputStream in = file.createInputStream(0L)) {
            MessageDigest digest = MessageDigest.getInstance(ALGORITHM);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                // HASH 可能跑在大文件上，ABOR 到达后应停止读取，避免浪费传输池线程。
                if (MoveCancellation.isCancelled()) {
                    session.write(new DefaultFtpReply(426, "Transfer aborted"));
                    return;
                }
                digest.update(buf, 0, n);
            }
            String hash = toHex(digest.digest());
            // 213 <algorithm> <hash-value> <pathname>
            session.write(new DefaultFtpReply(REPLY_213,
                    ALGORITHM + " " + hash + " " + file.getAbsolutePath()));
        } catch (Exception e) {
            session.write(new DefaultFtpReply(REPLY_551, "Error computing hash"));
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            chars[i * 2] = HEX[v >>> 4];
            chars[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(chars);
    }
}
