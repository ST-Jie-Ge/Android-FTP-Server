package org.apache.ftpserver.command.impl;

import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.ftpserver.command.AbstractCommand;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.impl.FtpIoSession;
import org.apache.ftpserver.impl.FtpServerContext;
import org.apache.ftpserver.impl.LocalizedFtpReply;

import java.io.IOException;
import java.util.Locale;

/**
 * {@code OPTS UTF8} — switches this connection to UTF-8.
 *
 * <p>Always answers 200, whatever the server-wide charset is. The stock implementation replied
 * 502 when the server ran in GBK, but that only produces broken behaviour: FEAT advertises
 * UTF8 unconditionally (it comes from the bundled {@code FtpStatus} template), clients send
 * {@code OPTS UTF8}, and modern clients then speak UTF-8 whether the server agreed or not.
 * The 502 left the server decoding UTF-8 bytes as GBK — garbled names, or 550 on every path.
 * Accepting the command and pinning UTF-8 to this session is what those clients expect;
 * sessions that say nothing keep the configured charset, so legacy GBK clients are unaffected.</p>
 */
public class OPTS_UTF8 extends AbstractCommand {
    public OPTS_UTF8() {
    }

    public void execute(FtpIoSession session, FtpServerContext context, FtpRequest request) throws IOException, FtpException {
        session.resetState();
        // "OPTS UTF8 OFF" is the only defined way back to the server-wide charset.
        String argument = request.getArgument();
        boolean disable = argument != null && argument.trim().toUpperCase(Locale.US).endsWith("OFF");
        FtpService.setSessionCharset(session, disable ? null : Constants.PreferenceConsts.CHARSET_TYPE_DEFAULT);
        session.write(LocalizedFtpReply.translate(session, request, context,
                200, "OPTS.UTF8", (String) null));
    }
}
