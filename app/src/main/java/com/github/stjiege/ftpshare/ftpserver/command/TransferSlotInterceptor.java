package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import com.github.stjiege.ftpshare.filesystem.TransferSlots;

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
 * 替代 {@link com.github.stjiege.ftpshare.services.TransferCommandGuard} 的传输槽/列举槽控制。
 */
final class TransferSlotInterceptor implements CommandInterceptor {

    private static final Set<String> TRANSFER_COMMANDS = new HashSet<>(
            Arrays.asList("STOR", "APPE", "STOU", "RETR"));
    private static final Set<String> LISTING_COMMANDS = new HashSet<>(
            Arrays.asList("LIST", "NLST", "MLSD"));

    @Override
    public boolean before(String commandName, FtpIoSession session, FtpServerContext context,
                          FtpRequest request) throws IOException, FtpException {
        if ("HASH".equals(commandName) && request.hasArgument()) {
            try {
                TransferSlots.acquireHash();
            } catch (IOException busy) {
                session.write(new DefaultFtpReply(450,
                        "Hash capacity unavailable; retry later."));
                return false;
            }
        } else if (isTransfer(commandName)) {
            try {
                TransferSlots.acquire();
            } catch (IOException busy) {
                session.resetState();
                SessionDataContext.get(session).closeDataConnection();
                session.write(new DefaultFtpReply(450,
                        "Transfer capacity unavailable; retry later."));
                return false;
            }
        } else if (isListing(commandName)) {
            try {
                TransferSlots.acquireListing();
            } catch (IOException busy) {
                session.resetState();
                SessionDataContext.get(session).closeDataConnection();
                session.write(new DefaultFtpReply(450,
                        "Directory listing capacity unavailable; retry later."));
                return false;
            }
        }
        return true;
    }

    @Override
    public void after(String commandName, FtpIoSession session, FtpServerContext context,
                      FtpRequest request, Throwable error) {
        if ("HASH".equals(commandName) && request.hasArgument()) {
            TransferSlots.releaseHash();
        } else if (isTransfer(commandName)) {
            TransferSlots.release();
        } else if (isListing(commandName)) {
            TransferSlots.releaseListing();
        }
    }

    private boolean isTransfer(String name) {
        return TRANSFER_COMMANDS.contains(name);
    }

    private boolean isListing(String name) {
        return LISTING_COMMANDS.contains(name);
    }
}
