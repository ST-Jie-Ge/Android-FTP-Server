package com.github.stjiege.ftpshare.ftpserver.command;

import com.github.stjiege.ftpshare.command.HashCommand;
import com.github.stjiege.ftpshare.command.MoveCommand;

import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.command.CommandFactory;
import org.apache.ftpserver.command.CommandFactoryFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直接按命令名路由，替代 {@code TransferCommandGuard + FtpsCommandGuard} 多层包装。
 *
 * <p>命令来源分三层：
 * <ol>
 *   <li>显式覆盖（如 HASH、FEAT）；</li>
 *   <li>Apache 默认命令工厂；</li>
 *   <li>未知命令返回 null，由 {@code DefaultFtpHandler} 回 502。</li>
 * </ol>
 * 每个返回的命令都会经过全局拦截器（传输槽、FTPS 策略、禁用命令、ASCII REST/SIZE）。</p>
 */
public final class CommandRouter implements CommandFactory {

    private final CommandFactory defaults;
    private final Map<String, Command> overrides = new HashMap<>();
    private final List<CommandInterceptor> interceptors = new ArrayList<>();
    private final Map<String, Command> cache = new ConcurrentHashMap<>();

    public CommandRouter(boolean tlsRequired) {
        CommandFactoryFactory factory = new CommandFactoryFactory();
        factory.setUseDefaultCommands(true);
        this.defaults = factory.createCommandFactory();

        registerOverrides();
        registerInterceptors(tlsRequired);
    }

    private void registerOverrides() {
        overrides.put("AVBL", new AvailableSpaceCommand());
        for (String name : new String[]{"LIST", "NLST", "MLSD"}) {
            overrides.put(name, new StreamingListingCommand(name));
        }
        overrides.put("PORT", new PortCommand());
        // Keep CCC visible to the TLS policy even though Apache does not implement it.
        overrides.put("CCC", new org.apache.ftpserver.command.NotSupportedCommand());
        overrides.put("HASH", new HashCommand());
        overrides.put("MOVE", new MoveCommand());
        overrides.put("FEAT", new org.apache.ftpserver.command.impl.FEAT());
    }

    private void registerInterceptors(boolean tlsRequired) {
        interceptors.add(new DisabledCommandInterceptor());
        interceptors.add(new FtpsPolicyInterceptor(tlsRequired));
        interceptors.add(new AsciiRestSizeInterceptor());
        interceptors.add(new TransferSlotInterceptor());
    }

    @Override
    public Command getCommand(String name) {
        Command wrapped = cache.get(name);
        if (wrapped != null) {
            return wrapped;
        }

        Command target = overrides.get(name);
        if (target == null) {
            target = defaults.getCommand(name);
        }
        if (target == null) {
            return null;
        }

        wrapped = new InterceptedCommand(name, target, interceptors);
        Command existing = cache.putIfAbsent(name, wrapped);
        return existing != null ? existing : wrapped;
    }
}
