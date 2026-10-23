package org.apache.ftpserver.listener.nio;

import com.github.stjiege.ftpshare.services.FtpService;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolEncoderAdapter;
import org.apache.mina.filter.codec.ProtocolEncoderOutput;

import java.nio.charset.Charset;

public class FtpResponseEncoder extends ProtocolEncoderAdapter {

    public void encode(IoSession session, Object message, ProtocolEncoderOutput out) throws Exception {
        String value = message.toString();
        // CharsetEncoder is NOT thread-safe: it keeps mutable buffer position/limit state.
        // A shared instance causes concurrent FTP sessions (multi-threaded clients) to corrupt
        // each other's encoder state, throwing IllegalArgumentException("Bad position N/M") wrapped
        // in CoderMalfunctionError, which crashes the whole process. Always create a fresh encoder.
        // The charset is the session's, so a client that negotiated UTF-8 with OPTS UTF8 gets
        // UTF-8 replies even when the server-wide setting is GBK.
        Charset charset = Charset.forName(FtpService.getSessionCharset(session));
        byte[] bytes = value.getBytes(charset);
        // 按编码后的字节数分配，而不是按字符数：UTF-8/GBK 等多字节字符集下按字符数会偏小，
        // 被迫依赖 autoExpand 反复扩容。getBytes(charset) 每次内部新建 encoder，同样规避了
        // 上面的 CharsetEncoder 非线程安全问题。
        IoBuffer buf = IoBuffer.allocate(bytes.length).setAutoExpand(true);
        buf.put(bytes);
        buf.flip();
        out.write(buf);
    }
}
