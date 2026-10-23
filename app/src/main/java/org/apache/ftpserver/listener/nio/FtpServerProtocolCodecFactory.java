package org.apache.ftpserver.listener.nio;

import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFactory;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.apache.mina.filter.codec.ProtocolEncoder;

public class FtpServerProtocolCodecFactory implements ProtocolCodecFactory {
    /**
     * One decoder instance serves every session, which is safe: {@code TextLineDecoder} keeps
     * its accumulated buffer in an {@code IoSession} attribute, never in the decoder itself.
     * Charset selection is per session inside {@link SessionCharsetLineDecoder}.
     */
    private final ProtocolDecoder decoder = new SessionCharsetLineDecoder();
    private final ProtocolEncoder encoder = new FtpResponseEncoder();

    public FtpServerProtocolCodecFactory() {
    }

    public ProtocolDecoder getDecoder(IoSession session) throws Exception {
        return this.decoder;
    }

    public ProtocolEncoder getEncoder(IoSession session) throws Exception {
        return this.encoder;
    }
}
