package org.apache.ftpserver.listener.nio;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.filterchain.IoFilter.NextFilter;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolDecoderOutput;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * A client that negotiates UTF-8 with OPTS UTF8 must be decoded as UTF-8 even when the server
 * runs in GBK, while a session that said nothing keeps the server charset. Both sessions share
 * one decoder instance here, which is how the listener uses it.
 */
public class SessionCharsetLineDecoderTest {

    private static final String NAME = "\u4e2d\u6587.txt";

    @Test public void decodesEachSessionWithItsOwnCharset() throws Exception {
        SessionCharsetLineDecoder decoder = new SessionCharsetLineDecoder();

        IoSession utf8 = new DummySession();
        FtpService.setSessionCharset(utf8, "UTF-8");
        IoSession gbk = new DummySession();
        FtpService.setSessionCharset(gbk, "GBK");

        assertEquals(NAME, decodeOne(decoder, utf8, line(NAME, "UTF-8")));
        assertEquals(NAME, decodeOne(decoder, gbk, line(NAME, "GBK")));
    }

    /** The whole point of the bug: UTF-8 bytes handed to a GBK session come out wrong. */
    @Test public void mismatchedBytesDoNotRoundTrip() throws Exception {
        SessionCharsetLineDecoder decoder = new SessionCharsetLineDecoder();
        IoSession gbk = new DummySession();
        FtpService.setSessionCharset(gbk, "GBK");

        assertNotEquals(NAME, decodeOne(decoder, gbk, line(NAME, "UTF-8")));
    }

    /**
     * A charset switch must not corrupt a half-received line: leftover raw bytes are re-decoded
     * with the new charset. This is exactly the bug the raw-byte buffer fixes — the old design
     * reused a GBK {@code CharsetDecoder} for the bytes that followed {@code OPTS UTF8}.
     */
    @Test public void charsetSwitchRedecodesLeftoverBytesWithNewCharset() throws Exception {
        SessionCharsetLineDecoder decoder = new SessionCharsetLineDecoder();
        IoSession session = new DummySession();
        FtpService.setSessionCharset(session, "GBK");

        // Feed the first half of a UTF-8 Chinese filename with no newline yet.
        byte[] utf8 = line(NAME, "UTF-8");
        byte[] firstHalf = new byte[] { utf8[0], utf8[1] };
        CollectingOutput out = new CollectingOutput();
        decoder.decode(session, IoBuffer.wrap(firstHalf), out);
        assertEquals("half line must not decode yet", 0, out.messages.size());

        // Now switch to UTF-8 and deliver the rest of the line.
        FtpService.setSessionCharset(session, "UTF-8");
        byte[] rest = new byte[utf8.length - 2];
        System.arraycopy(utf8, 2, rest, 0, rest.length);
        decoder.decode(session, IoBuffer.wrap(rest), out);

        assertEquals(1, out.messages.size());
        assertEquals(NAME, out.messages.get(0));
    }

    /**
     * OPTS UTF8 and the following Chinese command may arrive in one read. The decoder must emit
     * both as complete, distinct lines in order and must not lose or mis-split bytes. Whether the
     * second line is decoded as UTF-8 depends on whether OPTS has been executed by the time it is
     * decoded; the ordering and byte-preservation behaviour is what is asserted here.
     */
    @Test public void optsAndFollowingCommandInOneReadDecodeInOrder() throws Exception {
        SessionCharsetLineDecoder decoder = new SessionCharsetLineDecoder();
        IoSession session = new DummySession();
        FtpService.setSessionCharset(session, "UTF-8");

        byte[] bytes = ("OPTS UTF8 ON\r\nCWD " + NAME + "\r\n").getBytes(Charset.forName("UTF-8"));
        CollectingOutput out = new CollectingOutput();
        decoder.decode(session, IoBuffer.wrap(bytes), out);

        assertEquals(2, out.messages.size());
        assertEquals("OPTS UTF8 ON", out.messages.get(0));
        assertEquals("CWD " + NAME, out.messages.get(1));
    }

    private static byte[] line(String text, String charset) {
        return (text + "\r\n").getBytes(Charset.forName(charset));
    }

    private static String decodeOne(SessionCharsetLineDecoder decoder, IoSession session, byte[] bytes)
            throws Exception {
        CollectingOutput out = new CollectingOutput();
        decoder.decode(session, IoBuffer.wrap(bytes), out);
        return out.messages.isEmpty() ? null : (String) out.messages.get(0);
    }

    private static final class CollectingOutput implements ProtocolDecoderOutput {
        final List<Object> messages = new ArrayList<Object>();

        @Override public void write(Object message) {
            messages.add(message);
        }

        @Override public void flush(NextFilter nextFilter, IoSession session) {
        }
    }
}
