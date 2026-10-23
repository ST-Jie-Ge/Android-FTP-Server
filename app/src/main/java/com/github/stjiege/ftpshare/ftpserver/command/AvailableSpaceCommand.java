package com.github.stjiege.ftpshare.ftpserver.command;

import android.os.Build;
import android.os.StatFs;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpFile;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import java.io.File;
import java.io.IOException;

/** AVBL [directory]: 213 followed only by decimal available upload bytes. */
public final class AvailableSpaceCommand implements Command {
    @Override public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request) {
        try {
            String path = request.getArgument();
            FtpFile directory = path == null || path.isEmpty()
                    ? session.getFileSystemView().getWorkingDirectory()
                    : session.getFileSystemView().getFile(path);
            if (directory == null || !directory.doesExist() || !directory.isDirectory() || !directory.isReadable()) {
                reply(session, 550, "Directory unavailable or not permitted");
                return;
            }
            Object physical = directory.getPhysicalFile();
            if (!(physical instanceof File)) {
                reply(session, 550, "Select a storage directory to query available space");
                return;
            }
            File file = (File) physical;
            if (!file.isDirectory()) throw new IOException("Storage unavailable");
            long available;
            if (Build.VERSION.SDK_INT > 0) {
                StatFs stat = new StatFs(file.getAbsolutePath());
                if (stat.getTotalBytes() <= 0) throw new IOException("Storage unavailable");
                available = stat.getAvailableBytes();
            } else {
                // Host JVM; Android uses StatFs so query failures are not confused with a full disk.
                if (file.getTotalSpace() <= 0) throw new IOException("Storage unavailable");
                available = file.getUsableSpace();
            }
            // AVBL describes bytes the authenticated account may upload, not raw capacity.
            reply(session, 213, Long.toString(directory.isWritable() ? Math.max(0, available) : 0));
        } catch (IOException | FtpException | RuntimeException error) {
            reply(session, 550, "Cannot query available space for this directory");
        }
    }

    private static void reply(FtpIoSession session, int code, String message) {
        session.write(new DefaultFtpReply(code, message));
    }
}
