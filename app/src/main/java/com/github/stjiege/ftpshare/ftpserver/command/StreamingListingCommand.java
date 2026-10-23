package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.filesystem.MoveCancellation;
import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;
import com.github.stjiege.ftpshare.services.FtpService;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.impl.listing.*;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.impl.ServerDataConnectionFactory;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.Charset;

/** Shared LIST/NLST/MLSD command for both command routers and both data transports. */
public final class StreamingListingCommand implements Command {
    private final String name;
    public StreamingListingCommand(String name) { this.name = name; }

    @Override public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request)
            throws IOException, FtpException {
        session.resetState();
        ServerDataConnectionFactory factory = SessionDataContext.get(session);
        session.setTransferActive(true);
        boolean started = false;
        try {
            if (factory.getInetAddress() == null) {
                reply(session, 503, "PORT or PASV must be issued first");
                return;
            }
            // MLSD takes a pathname, not LIST switches/globs; preserve spaces and wildcard names.
            ListArgument argument = "MLSD".equals(name)
                    ? new ListArgument(request.getArgument() == null ? "." : request.getArgument(), null, new char[0])
                    : ListArgumentParser.parse(request.getArgument());
            FileSystemView view = session.getFileSystemView();
            FtpFile target = view.getFile(argument.getFile());
            if (target == null || !target.doesExist() || !target.isReadable()
                    || ("MLSD".equals(name) && !target.isDirectory())) {
                reply(session, 550, "Listing path is unavailable or not permitted");
                return;
            }
            FileFormater formatter = "MLSD".equals(name)
                    ? new MLSTFileFormater((String[]) session.getAttribute("MLST.types"))
                    : "NLST".equals(name) && !argument.hasOption('l')
                    ? new NLSTFileFormater() : new LISTFileFormater();
            Charset charset = Charset.forName(FtpService.getSessionCharset(session));
            try (ListingInputStream input = new ListingInputStream(view, target, argument, formatter, charset)) {
                reply(session, 150, "Opening data connection for " + name);
                DataConnection connection;
                try { connection = factory.openConnection(); }
                catch (Exception error) {
                    reply(session, MoveCancellation.isCancelled() ? 426 : 425, "Cannot open listing data connection");
                    return;
                }
                started = true;
                connection.transferToClient(session.getFtpletSession(), input);
            }
            if (MoveCancellation.isCancelled()) reply(session, 426, "Directory listing cancelled");
            else reply(session, 226, "Directory listing complete");
        } catch (IllegalArgumentException error) {
            reply(session, 501, "Invalid listing argument");
        } catch (IOException | FtpException error) {
            int code = MoveCancellation.isCancelled() || error instanceof SocketException ? 426 : started ? 451 : 550;
            reply(session, code, "Directory listing failed");
        } finally {
            session.setTransferActive(false);
            session.resetState();
            factory.closeDataConnection();
        }
    }

    private static void reply(FtpIoSession session, int code, String message) {
        session.write(new DefaultFtpReply(code, message));
    }
}
