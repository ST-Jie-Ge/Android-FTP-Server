package com.github.stjiege.ftpshare.ftpserver.command;

import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import java.io.IOException;

/**
 * 命令执行前后的横切拦截器。
 */
public interface CommandInterceptor {

    /**
     * 在目标命令前执行。
     *
     * @return false 表示已自行回复客户端并终止命令执行。
     */
    boolean before(String commandName, FtpIoSession session, FtpServerContext context,
                   FtpRequest request) throws IOException, FtpException;

    /**
     * 在目标命令后执行，无论是否抛异常。
     */
    void after(String commandName, FtpIoSession session, FtpServerContext context,
               FtpRequest request, Throwable error);
}
