package com.github.stjiege.ftpshare.services;

import com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext;

import com.github.stjiege.ftpshare.filesystem.TransferSlots;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.command.CommandFactoryFactory;
import org.apache.ftpserver.command.NotSupportedCommand;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import java.io.IOException;

/**
 * FTP 命令工厂的装配点：在 {@code FtpService.startFTPService()} 里安装一次。
 *
 * 目前承担三件事：
 * ① <b>传输槽闸门</b>——STOR/APPE/STOU/RETR 在打开或截断文件前先抢全局并发槽，
 *   抢不到回 450，保护的是 fd 与内存（多线程客户端不开闸会耗尽 Android 的
 *   文件描述符）；
 * ② <b>目录列举闸门</b>——LIST/NLST/MLSD 走 {@link TransferSlots#acquireListing()}，
 *   与传输槽是两把独立的信号量：浏览目录不该因为正在传文件就被拒，但它们同样吃
 *   传输池线程（被动模式一次列举最长要等满 idleTime 的 accept），不加闸门就能把
 *   池占满、让真正的传输排队到 421；
 * ③ <b>禁用阻塞命令</b>——MD5/MMD5 会在命令线程上同步读整个文件算哈希，
 *   1 GB 文件 ≈ 10s+，期间该 worker 什么都干不了；FTP 客户端几乎不依赖它
 *   （属可选校验扩展，主流客户端默认不发），属"免费的 DoS 命令"，直接禁用。
 *   FEAT 无需另行摘除：上游 1.1.1 的 211.FEAT 模板本来就不通告 MD5/MMD5
 *   （jar 内 FtpStatus.properties 已核对），客户端不会误以为服务端支持。
 */
public final class TransferCommandGuard implements Command {
    /** 受传输槽保护的命令。 */
    static final String[] TRANSFER_COMMANDS = {"STOR", "APPE", "STOU", "RETR"};
    /** 受列举闸门保护的命令；与 {@code DefaultFtpHandler.isTransferCommand} 的池派发范围对齐。 */
    static final String[] LISTING_COMMANDS = {"LIST", "NLST", "MLSD"};

    private final Command delegate;
    private final boolean listing;

    private TransferCommandGuard(Command delegate, boolean listing) {
        this.delegate = delegate;
        this.listing = listing;
    }

    /** MD5/MMD5 的替身：回 502 Not supported。无状态，全局单例即可。 */
    private static final Command NOT_SUPPORTED = new NotSupportedCommand();

    /** 交给命令工厂 lambda 的判断，也便于单测。 */
    static boolean isDisabledCommand(String name) {
        return "MD5".equals(name) || "MMD5".equals(name);
    }

    public static void install(FtpServerFactory server) {
        CommandFactory defaults = server.getCommandFactory();
        CommandFactoryFactory guarded = new CommandFactoryFactory();
        for (String name : TRANSFER_COMMANDS) {
            addGuarded(guarded, defaults, name, false);
        }
        for (String name : LISTING_COMMANDS) {
            addGuarded(guarded, defaults, name, true);
        }
        CommandFactory overrides = guarded.createCommandFactory();
        server.setCommandFactory(name -> {
            if ("PORT".equals(name)) return new com.github.stjiege.ftpshare.ftpserver.command.PortCommand();
            if ("REST".equals(name) || "SIZE".equals(name)) {
                return (session, context, request) -> {
                    if (session.getDataType() == org.apache.ftpserver.ftplet.DataType.ASCII) {
                        session.resetState();
                        session.write(new DefaultFtpReply("SIZE".equals(name) ? 550 : 504,
                                "ASCII " + name + " is not supported; use TYPE I."));
                    } else defaults.getCommand(name).execute(session, context, request);
                };
            }
            if (isDisabledCommand(name)) {
                return NOT_SUPPORTED;
            }
            if (isGuarded(name)) {
                return overrides.getCommand(name);
            }
            return defaults.getCommand(name);
        });
    }

    /**
     * 命令未注册时（{@code getCommand} 返回 null）不要包一层空 delegate：那样命令工厂会
     * 返回一个"存在"的命令，执行时才 NPE；保持原样则由上层回 502，语义才是对的。
     */
    private static void addGuarded(CommandFactoryFactory target, CommandFactory defaults,
                                   String name, boolean listing) {
        Command original = listing
                ? new com.github.stjiege.ftpshare.ftpserver.command.StreamingListingCommand(name)
                : defaults.getCommand(name);
        if (original == null) return;
        target.addCommand(name, new TransferCommandGuard(original, listing));
    }

    private static boolean isGuarded(String name) {
        for (String candidate : TRANSFER_COMMANDS) if (candidate.equals(name)) return true;
        for (String candidate : LISTING_COMMANDS) if (candidate.equals(name)) return true;
        return false;
    }

    @Override public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request)
            throws IOException, FtpException {
        try {
            if (listing) TransferSlots.acquireListing();
            else TransferSlots.acquire();
        } catch (IOException busy) {
            // No stream has been opened, so a rejected overwrite preserves the existing file.
            session.resetState();
            SessionDataContext.get(session).closeDataConnection();
            session.write(new DefaultFtpReply(450,
                    listing ? "Directory listing capacity unavailable; retry later."
                            : "Transfer capacity unavailable; retry later."));
            return;
        }
        try {
            delegate.execute(session, context, request);
        } finally {
            if (listing) TransferSlots.releaseListing();
            else TransferSlots.release();
        }
    }
}
