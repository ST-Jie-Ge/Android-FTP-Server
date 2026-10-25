package org.apache.ftpserver.command.impl;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.ftplet.DefaultFtpReply;
import org.apache.ftpserver.impl.LocalizedFtpReply;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code OPTS UTF8} — switches this connection to UTF-8 in adaptive mode.
 *
 * <p>Adaptive mode accepts the request per session; forced modes reject requests that would
 * change the configured encoding. The session charset is shared by command decoding, replies,
 * and directory listings.</p>
 */
public class OPTS_UTF8 extends AbstractCommand {
    public OPTS_UTF8() {
    }

    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request) throws IOException, FtpException {
        session.resetState();
        String mode = FtpService.getActiveFtpCharsetMode();
        String argument = request.getArgument();
        boolean disable = argument != null && argument.trim().toUpperCase(Locale.US).endsWith("OFF");
        if (Constants.Charset.CHARSET_AUTO.equals(mode)) {
            FtpService.setSessionCharset(session, disable ? null : Constants.Charset.CHAR_UTF);
        } else if (Constants.Charset.CHAR_UTF.equals(mode) && disable) {
            session.write(new DefaultFtpReply(504, "UTF-8 is forced by server settings"));
            return;
        } else if (Constants.Charset.CHAR_GBK.equals(mode) && !disable) {
            session.write(new DefaultFtpReply(504, "UTF-8 is disabled by server settings"));
            return;
        }
        session.write(LocalizedFtpReply.translate(session, request, context,
                200, "OPTS.UTF8", (String) null));
    }
}
