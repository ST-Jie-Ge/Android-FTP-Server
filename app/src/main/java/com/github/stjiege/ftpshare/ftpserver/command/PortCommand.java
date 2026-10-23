package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.ftpserver.data.ActiveConnectionFactory;
import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;
import org.apache.ftpserver.DataConnectionConfiguration;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.util.SocketAddressEncoder;
import java.net.InetSocketAddress;

/** PORT uses the shared data context with either connection backend. */
public final class PortCommand implements Command {
    @Override public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request) {
        session.resetState();
        DataConnectionConfiguration configuration = session.getListener().getDataConnectionConfiguration();
        if (!configuration.isActiveEnabled()) {
            session.write(new DefaultFtpReply(501, "Active mode is disabled; use PASV or EPSV."));
            return;
        }
        try {
            if (!request.hasArgument()) throw new IllegalArgumentException("PORT requires an endpoint");
            InetSocketAddress endpoint = SocketAddressEncoder.decode(request.getArgument());
            if (endpoint.getPort() == 0) throw new IllegalArgumentException("PORT requires a nonzero port");
            if (configuration.isActiveIpCheck() && !ActiveConnectionFactory.sameAddress(endpoint.getAddress(),
                    ((InetSocketAddress) session.getRemoteAddress()).getAddress())) {
                session.write(new DefaultFtpReply(501, "PORT address must match the control connection."));
                return;
            }
            SessionDataContext.get(session).initActiveDataConnection(endpoint);
            session.write(new DefaultFtpReply(200, "PORT command successful."));
        } catch (Exception error) {
            session.write(new DefaultFtpReply(501, "Invalid PORT endpoint."));
        }
    }
}
