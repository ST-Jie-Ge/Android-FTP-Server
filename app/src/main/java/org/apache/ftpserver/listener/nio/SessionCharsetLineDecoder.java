package org.apache.ftpserver.listener.nio;

import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolDecoderException;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;

import java.io.ByteArrayOutputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/**
 * Control-connection decoder that decodes each command line with the charset the session holds
 * <em>at the time the line is complete</em>.
 *
 * <p>This replaces the previous design, which cached one {@code TextLineDecoder} per charset and
 * delegated to it. That design was subtly broken: MINA's {@code TextLineDecoder} keeps its
 * half-decoded state (a {@code Context} holding a fixed {@code CharsetDecoder}) in a single static
 * session attribute. Once a session switched charset — {@code OPTS UTF8 ON} while the server ran
 * GBK — the existing {@code Context} was reused, so subsequent UTF-8 command bytes kept being
 * decoded by the old GBK {@code CharsetDecoder}: garbled names or 550 on every path.</p>
 *
 * <p>The fix stores <b>raw bytes</b> per session, not a partially decoded string. A complete line
 * is recognised on the byte level (LF is a single-byte ASCII character that never appears inside a
 * UTF-8 or GBK multi-byte sequence), and only then decoded with the session's <em>current</em>
 * charset. Switching charset therefore cannot corrupt a half-received line — the leftover bytes
 * are simply decoded with the new charset when the line completes. This also handles the case where
 * {@code OPTS UTF8 ON} and a following Chinese command arrive in one network read: each line is
 * decoded in order with the charset in effect when it completes, so the post-{@code OPTS} command
 * is decoded as UTF-8 rather than being prematurely decoded as GBK.</p>
 *
 * <p>The stock {@code maxLineLength} of 1024 is preserved so an unbounded line cannot exhaust
 * memory; the command-line length limit and ABOR's immediate-cancel behaviour are unaffected
 * because this decoder still runs on the I/O thread and emits lines as soon as they are complete.</p>
 */
public final class SessionCharsetLineDecoder implements ProtocolDecoder {

    /** Per-session raw-byte accumulator; survives across decode() calls and charset switches. */
    private static final String ATTR_PENDING = SessionCharsetLineDecoder.class.getName() + ".pending";

    /** Matches the stock TextLineDecoder default; a command line longer than this is rejected. */
    private static final int MAX_LINE_LENGTH = 1024;

    @Override
    public void decode(IoSession session, IoBuffer in, ProtocolDecoderOutput out) throws Exception {
        ByteArrayOutputStream pending = pending(session);
        // Append everything left in the input buffer as raw bytes.
        int remaining = in.remaining();
        if (remaining > 0) {
            byte[] chunk = new byte[remaining];
            in.get(chunk);
            pending.write(chunk, 0, remaining);
        }

        byte[] buffer = pending.toByteArray();
        int lineStart = 0;
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] != '\n') continue;
            // Strip a preceding '\r' (CRLF) and the LF itself.
            int end = i;
            if (end > lineStart && buffer[end - 1] == '\r') end--;
            String line = decodeLine(session, buffer, lineStart, end - lineStart);
            out.write(line);
            lineStart = i + 1;
        }

        if (lineStart > 0) {
            // Keep whatever followed the last newline (a possibly empty remainder).
            pending.reset();
            pending.write(buffer, lineStart, buffer.length - lineStart);
        }
        // Enforce the stock single-line cap on the not-yet-complete remainder.
        if (buffer.length - lineStart > MAX_LINE_LENGTH) {
            throw new ProtocolDecoderException("Line is too long: " + (buffer.length - lineStart) + " bytes");
        }
    }

    private static String decodeLine(IoSession session, byte[] buffer, int offset, int length)
            throws ProtocolDecoderException {
        String charsetName = FtpService.getSessionCharset(session);
        Charset charset;
        try {
            charset = Charset.forName(charsetName);
        } catch (RuntimeException invalid) {
            charset = Charset.defaultCharset();
        }
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(buffer, offset, length)).toString();
        } catch (CharacterCodingException e) {
            // Keep the cause so DefaultFtpHandler.exceptionCaught can tell a malformed-input
            // error apart and reply 501 "Invalid character in command".
            throw new ProtocolDecoderException("Invalid character in command", e);
        }
    }

    @Override
    public void finishDecode(IoSession session, ProtocolDecoderOutput out) throws Exception {
        // Nothing buffered means nothing to flush; a dangling half-line is not a valid command.
    }

    @Override
    public void dispose(IoSession session) throws Exception {
        session.removeAttribute(ATTR_PENDING);
    }

    private static ByteArrayOutputStream pending(IoSession session) {
        ByteArrayOutputStream pending = (ByteArrayOutputStream) session.getAttribute(ATTR_PENDING);
        if (pending == null) {
            pending = new ByteArrayOutputStream();
            session.setAttribute(ATTR_PENDING, pending);
        }
        return pending;
    }
}
