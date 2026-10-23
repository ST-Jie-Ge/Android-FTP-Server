package com.github.stjiege.ftpshare.ftpserver.command;

import org.apache.ftpserver.ftplet.DataType;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;

import java.io.IOException;

/**
 * ASCII 模式下 REST/SIZE 必须拒绝，因为传输层不再做行尾转换，ASCII REST 语义不可实现。
 */
final class AsciiRestSizeInterceptor implements CommandInterceptor {

    @Override
    public boolean before(String commandName, FtpIoSession session, FtpServerContext context,
                          FtpRequest request) throws IOException, FtpException {
        if (("REST".equals(commandName) || "SIZE".equals(commandName))
                && session.getDataType() == DataType.ASCII) {
            session.resetState();
            session.write(new DefaultFtpReply("SIZE".equals(commandName) ? 550 : 504,
                    "ASCII " + commandName + " is not supported; use TYPE I."));
            return false;
        }
        return true;
    }

    @Override
    public void after(String commandName, FtpIoSession session, FtpServerContext context,
                      FtpRequest request, Throwable error) { }
}
