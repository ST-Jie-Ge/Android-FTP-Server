package com.github.stjiege.ftpshare.ftpserver.command;

import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.NotSupportedCommand;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import java.io.IOException;

/**
 * 禁用 MD5/MMD5 等 CPU-DoS 命令。
 */
final class DisabledCommandInterceptor implements CommandInterceptor {

    /** NotSupportedCommand is stateless; reuse a single instance to avoid per-request allocation. */
    private static final Command NOT_SUPPORTED = new NotSupportedCommand();

    @Override
    public boolean before(String commandName, FtpIoSession session, FtpServerContext context,
                          FtpRequest request) throws IOException, FtpException {
        if (isDisabled(commandName)) {
            NOT_SUPPORTED.execute(session, context, request);
            return false;
        }
        return true;
    }

    @Override
    public void after(String commandName, FtpIoSession session, FtpServerContext context,
                      FtpRequest request, Throwable error) { }

    private boolean isDisabled(String name) {
        return "MD5".equals(name) || "MMD5".equals(name);
    }
}
