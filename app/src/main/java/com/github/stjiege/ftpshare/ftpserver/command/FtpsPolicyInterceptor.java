package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 替代 {@link com.github.stjiege.ftpshare.services.FtpsCommandGuard} 的 FTPS 策略强制。
 */
final class FtpsPolicyInterceptor implements CommandInterceptor {

    private static final Set<String> ALLOWED_BEFORE_TLS = new HashSet<>(
            Arrays.asList("AUTH", "FEAT", "SYST", "QUIT", "NOOP"));
    private static final Set<String> DATA_COMMANDS = new HashSet<>(
            Arrays.asList("RETR", "STOR", "STOU", "APPE", "LIST", "NLST", "MLSD"));

    private final boolean tlsRequired;

    FtpsPolicyInterceptor(boolean tlsRequired) {
        this.tlsRequired = tlsRequired;
    }

    @Override
    public boolean before(String commandName, FtpIoSession session, FtpServerContext context,
                          FtpRequest request) throws IOException, FtpException {
        if (!tlsRequired) return true;

        if (!session.isSecure() && !allowedBeforeTls(commandName)) {
            session.write(new DefaultFtpReply(534, "TLS required. Use AUTH TLS before login."));
            return false;
        }
        if ("CCC".equals(commandName)
                || ("PROT".equals(commandName) && !"P".equalsIgnoreCase(request.getArgument()))) {
            session.write(new DefaultFtpReply(534, "Private TLS protection is required."));
            return false;
        }
        if (isDataCommand(commandName) && !SessionDataContext.get(session).isSecure()) {
            session.write(new DefaultFtpReply(534,
                    "Use PBSZ 0 and PROT P before transferring data."));
            return false;
        }
        return true;
    }

    @Override
    public void after(String commandName, FtpIoSession session, FtpServerContext context,
                      FtpRequest request, Throwable error) { }

    private static boolean allowedBeforeTls(String name) {
        return ALLOWED_BEFORE_TLS.contains(name);
    }

    private static boolean isDataCommand(String name) {
        return DATA_COMMANDS.contains(name);
    }
}
