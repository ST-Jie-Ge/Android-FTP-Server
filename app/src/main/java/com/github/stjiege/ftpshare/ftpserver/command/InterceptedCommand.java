package com.github.stjiege.ftpshare.ftpserver.command;

import org.apache.ftpserver.command.Command;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import java.io.IOException;
import java.util.List;

/**
 * 把一组拦截器和最终命令串成一条链。实现 Apache {@link Command} 接口，
 * 所以可以直接塞进 {@link org.apache.ftpserver.command.CommandFactory}。
 */
final class InterceptedCommand implements Command {

    private final String commandName;
    private final Command target;
    private final List<CommandInterceptor> interceptors;

    InterceptedCommand(String commandName, Command target, List<CommandInterceptor> interceptors) {
        this.commandName = commandName;
        this.target = target;
        this.interceptors = interceptors;
    }

    @Override
    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request)
            throws IOException, FtpException {
        for (CommandInterceptor interceptor : interceptors) {
            if (!interceptor.before(commandName, session, context, request)) {
                return;
            }
        }
        Throwable error = null;
        try {
            target.execute(session, context, request);
        } catch (IOException | FtpException e) {
            error = e;
            throw e;
        } catch (RuntimeException e) {
            error = e;
            throw e;
        } finally {
            for (CommandInterceptor interceptor : interceptors) {
                try {
                    interceptor.after(commandName, session, context, request, error);
                } catch (RuntimeException ignored) {
                    // 拦截器的 after 决不能吞掉原始异常或让命令层崩溃。
                }
            }
        }
    }
}
