package com.github.stjiege.ftpshare.ftpserver.data;

import com.github.stjiege.ftpshare.utils.LogManager;
import org.apache.ftpserver.ssl.ClientAuth;
import org.apache.ftpserver.ssl.SslConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/** Layer TLS over connected TCP using the control listener's existing SSLContext/cache. */
public final class TlsSocketFactory {
    private static final Logger LOG = LoggerFactory.getLogger(TlsSocketFactory.class);
    private static final AtomicLong FULL = new AtomicLong();
    private static final AtomicLong RESUMED = new AtomicLong();

    public Socket secure(Socket plain, SslConfiguration configuration, int timeoutMillis,
                         ActiveConnectionFactory.SocketOwner owner) throws Exception {
        if (configuration == null) throw new IOException("Data TLS is not configured");
        SSLSocket tls = null;
        long start = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        try {
            // Do not create a new SSLContext here: it would discard TLS 1.2 session resumption.
            tls = (SSLSocket) configuration.getSocketFactory().createSocket(plain,
                    plain.getInetAddress().getHostAddress(), plain.getPort(), true);
            owner.publish(tls);
            tls.setUseClientMode(false); // The FTP server is the TLS server even for PORT.
            tls.setEnabledProtocols(new String[]{"TLSv1.2"});
            tls.setSoTimeout(timeoutMillis);
            if (configuration.getEnabledCipherSuites() != null) {
                tls.setEnabledCipherSuites(configuration.getEnabledCipherSuites());
            }
            if (configuration.getClientAuth() == ClientAuth.NEED) tls.setNeedClientAuth(true);
            else if (configuration.getClientAuth() == ClientAuth.WANT) tls.setWantClientAuth(true);
            tls.startHandshake(); // Required even when LIST returns an empty, valid directory.
            logHandshake(tls, startedAt, (System.nanoTime() - start) / 1_000_000L);
            return tls;
        } catch (Exception error) {
            try { if (tls != null) tls.close(); } catch (IOException ignored) { }
            try { plain.close(); } catch (IOException ignored) { }
            LogManager.warning("FTPS 数据握手失败: " + error.getClass().getSimpleName()
                    + "，耗时 " + ((System.nanoTime() - start) / 1_000_000L) + " ms");
            throw error;
        }
    }

    private void logHandshake(SSLSocket socket, long startedAt, long millis) {
        try {
            SSLSession established = socket.getSession();
            // JSSE has no portable isResumed API. Creation time is only a diagnostic estimate.
            boolean resumed = established.getCreationTime() < startedAt;
            if (resumed) RESUMED.incrementAndGet(); else FULL.incrementAndGet();
            long resumedCount = RESUMED.get();
            long total = resumedCount + FULL.get();
            String mode = resumed ? "resumed-estimate" : "full-estimate";
            LOG.info("FtpsHandshake: data connection handshake took {} ms, {}, suite={}",
                    millis, mode, established.getCipherSuite());
            if (total % 20 == 0) LOG.info("FtpsHandshake stats (estimated): {} resumed of {}", resumedCount, total);
            if (millis >= 150) LogManager.warning("FTPS 数据连接握手耗时 " + millis + " ms（" + mode
                    + "，套件 " + established.getCipherSuite() + "，估计复用率 " + (resumedCount * 100 / total) + "%）");
        } catch (RuntimeException error) {
            LOG.debug("Cannot read TLS handshake diagnostics", error);
        }
    }
}
