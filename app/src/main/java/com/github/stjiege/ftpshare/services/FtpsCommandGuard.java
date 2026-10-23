package com.github.stjiege.ftpshare.services;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import com.github.stjiege.ftpshare.command.HashCommand;
import com.github.stjiege.ftpshare.command.MoveCommand;
import com.github.stjiege.ftpshare.filesystem.TransferSlots;

import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.ftplet.DefaultFtpReply;

/** Explicit FTPS: refuse cleartext login and cleartext data, including directory listings. */
public final class FtpsCommandGuard {
    private FtpsCommandGuard() {}
    /** Compatibility for callers that install the FTPS-only guard. */
    public static void install(FtpServerFactory server) { install(server, true); }
    /**
     * 安装显式 FTPS 安全策略，并为 ftpshare 扩展命令（如 HASH）提供注册入口。
     *
     * @param tlsRequired true 表示当前启用了 FTPS，必须 AUTH TLS 之后才能执行非白名单命令，
     *                    且数据命令必须 PROT P；false 表示明文 FTP，只注册扩展命令而不做 TLS 强制。
     */
    public static void install(FtpServerFactory server, boolean tlsRequired) {
        CommandFactory previous = server.getCommandFactory();
        server.setCommandFactory(name -> {
            final Command command;
            Command delegate = previous.getCommand(name);
            if (delegate == null) {
                // HASH / MOVE 是 ftpshare 扩展，不在 Apache 原生命令工厂里。
                // CCC 在 FTPS 模式下明确拒绝；明文模式下也拒绝，因为不存在 TLS 上下文。
                if ("HASH".equals(name)) {
                    command = new HashCommand();
                } else if ("MOVE".equals(name)) {
                    command = new MoveCommand();
                } else if ("AVBL".equals(name)) {
                    command = new com.github.stjiege.ftpshare.ftpserver.command.AvailableSpaceCommand();
                } else if ("CCC".equals(name)) {
                    command = null;
                } else {
                    return null;
                }
            } else {
                command = delegate;
            }
            return (session, context, request) -> {
                if (tlsRequired && !session.isSecure() && !allowedBeforeTls(name)) {
                    session.write(new DefaultFtpReply(534, "TLS required. Use AUTH TLS before login."));
                    return;
                }
                if ("CCC".equals(name) || (tlsRequired && "PROT".equals(name) && !"P".equalsIgnoreCase(request.getArgument()))) {
                    session.write(new DefaultFtpReply(534, "Private TLS protection is required."));
                    return;
                }
                if (tlsRequired && isDataCommand(name) && !SessionDataContext.get(session).isSecure()) {
                    session.write(new DefaultFtpReply(534, "Use PBSZ 0 and PROT P before transferring data."));
                    return;
                }
                boolean hash = "HASH".equals(name) && request.hasArgument();
                if (hash) {
                    try {
                        TransferSlots.acquireHash();
                    } catch (java.io.IOException busy) {
                        session.write(new DefaultFtpReply(450,
                                "Hash capacity unavailable; retry later."));
                        return;
                    }
                }
                try {
                    command.execute(session, context, request);
                } finally {
                    if (hash) TransferSlots.releaseHash();
                }
            };
        });
    }
    private static boolean allowedBeforeTls(String name) {
        return "AUTH".equals(name) || "FEAT".equals(name) || "SYST".equals(name) || "QUIT".equals(name) || "NOOP".equals(name);
    }
    private static boolean isDataCommand(String name) {
        return "RETR".equals(name) || "STOR".equals(name) || "STOU".equals(name) || "APPE".equals(name)
                || "LIST".equals(name) || "NLST".equals(name) || "MLSD".equals(name);
    }
}
