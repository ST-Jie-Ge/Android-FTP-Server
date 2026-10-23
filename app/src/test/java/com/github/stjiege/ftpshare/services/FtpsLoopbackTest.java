package com.github.stjiege.ftpshare.services;

import org.apache.ftpserver.impl.DefaultFtpHandler;



import org.apache.ftpserver.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfiguration;

import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import javax.net.ssl.*;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real MINA control channel and JSSE data sockets; no Android device required. */
@org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
public class FtpsLoopbackTest {
    @org.junit.runners.Parameterized.Parameters(name="legacyData={0}, legacyCommands={1}, localFilesystem={2}")
    public static Object[][] configurations() {
        return new Object[][] {
            {false, false, false}, {false, true, false}, {true, false, false}, {true, true, false},
            {false, false, true}, {false, true, true}, {true, false, true}, {true, true, true}};
    }
    private final boolean legacyData;
    private final boolean legacyCommands;
    private final boolean localFilesystem;
    public FtpsLoopbackTest(boolean legacyData, boolean legacyCommands, boolean localFilesystem) {
        this.legacyData = legacyData; this.legacyCommands = legacyCommands; this.localFilesystem = localFilesystem;
    }
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private FtpServer server;
    private ExecutorService workers;
    private Socket control;
    private BufferedReader input;
    private BufferedWriter output;
    private SSLContext client;
    private Field charset;
    private Object oldCharset;
    private File home;
    private boolean extendedPassive;
    private boolean extendedActive;

    @Before public void start() throws Exception { start(true); }

    private void start(boolean ftps) throws Exception {
        charset = FtpService.class.getDeclaredField("activeFtpCharset");
        charset.setAccessible(true);
        oldCharset = charset.get(null);
        charset.set(null, "UTF-8");
        home = files.newFolder();
        File keyFile = files.newFile();
        try (InputStream in = getClass().getResourceAsStream("/ftps-test.p12")) {
            Files.copy(in, keyFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keyFile)) { trust.load(in, "test-only".toCharArray()); }
        SslConfiguration ssl = new FtpsConfiguration((java.security.PrivateKey) trust.getKey("ftps-test", "test-only".toCharArray()),
                (java.security.cert.X509Certificate) trust.getCertificate("ftps-test"));
        TrustManagerFactory tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(trust);
        client = SSLContext.getInstance("TLSv1.2");
        client.init(null, tm.getTrustManagers(), null);
        FtpServerFactory factory = new FtpServerFactory();
        if (localFilesystem) factory.setFileSystem(new com.github.stjiege.ftpshare.filesystem.LocalFileSystemTestFactory());
        else factory.setFileSystem(new com.github.stjiege.ftpshare.filesystem.SafeUploadFileSystemFactory(factory.getFileSystem()));
        BaseUser user = new BaseUser();
        user.setName("test"); user.setPassword("test"); user.setHomeDirectory(home.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));
        factory.getUserManager().save(user);
        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1"); listener.setPort(0);
        if (ftps) listener.setSslConfiguration(ssl);
        listener.setImplicitSsl(false);
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1"); data.setPassiveIpCheck(true);
        data.setIdleTime(5);
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            data.setPassivePorts(Integer.toString(free.getLocalPort()));
        }
        if (ftps) data.setSslConfiguration(ssl);
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        factory.addListener("default", listener.createListener());
        com.github.stjiege.ftpshare.ftpserver.data.SessionDataContext.configure(factory.getListener("default"), legacyData);
        if (legacyCommands) {
            TransferCommandGuard.install(factory);
            FtpsCommandGuard.install(factory, ftps);
        } else factory.setCommandFactory(new com.github.stjiege.ftpshare.ftpserver.command.CommandRouter(ftps));
        workers = Executors.newFixedThreadPool(2);
        DefaultFtpHandler.setTransferExecutor(workers);
        server = factory.createServer(); server.start();
        control = new Socket("127.0.0.1", factory.getListener("default").getPort());
        streams(control); reply(220);
    }

    @After public void stop() throws Exception {
        if (control != null) control.close();
        if (server != null) server.stop();
        DefaultFtpHandler.setTransferExecutor(null);
        if (workers != null) { workers.shutdownNow(); workers.awaitTermination(5, TimeUnit.SECONDS); }
        if (charset != null) charset.set(null, oldCharset);
    }

    private void streams(Socket socket) throws Exception {
        socket.setSoTimeout(5000);
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }
    private String reply(int code) throws Exception {
        String line = input.readLine();
        assertNotNull("Control connection closed", line);
        assertTrue(line, line.startsWith(Integer.toString(code)));
        StringBuilder response = new StringBuilder(line);
        if (line.length() > 3 && line.charAt(3) == '-') {
            do { line = input.readLine(); assertNotNull(line); response.append('\n').append(line); }
            while (!line.startsWith(code + " "));
        }
        return response.toString();
    }
    private String command(String text, int code) throws Exception {
        output.write(text + "\r\n"); output.flush(); return reply(code);
    }
    private void secureLogin() throws Exception {
        command("AUTH TLS", 234);
        SSLSocket secured = (SSLSocket) client.getSocketFactory().createSocket(control, "localhost", control.getPort(), true);
        secured.setEnabledProtocols(new String[]{"TLSv1.2"});
        control = secured;
        secured.setSoTimeout(5000); secured.startHandshake(); streams(control);
        command("USER test", 331); command("PASS test", 230);
    }
    private void login() throws Exception {
        secureLogin();
        command("PBSZ 0", 200); command("PROT P", 200);
    }
    private int passivePort() throws Exception {
        if (extendedPassive) {
            String response = command("EPSV", 229);
            String tuple = response.substring(response.indexOf('(') + 1, response.indexOf(')'));
            return Integer.parseInt(tuple.substring(3, tuple.length() - 1));
        }
        String response = command("PASV", 227);
        String[] parts = response.substring(response.indexOf('(') + 1, response.indexOf(')')).split(",");
        int port = Integer.parseInt(parts[4]) * 256 + Integer.parseInt(parts[5]);
        return port;
    }
    private SSLSocket passive() throws Exception {
        SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket("127.0.0.1", passivePort());
        socket.setEnabledProtocols(new String[]{"TLSv1.2"}); socket.setSoTimeout(5000);
        return socket;
    }
    private byte[] download(String request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (SSLSocket data = passive()) {
            command(request, 150); data.startHandshake();
            byte[] buffer = new byte[4096]; int count;
            while ((count = data.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        reply(226); return bytes.toByteArray();
    }
    @Test public void featureDiscoveryBeforeLogin() throws Exception {
        assertTrue(command("FEAT", 211).contains("AUTH TLS"));
        command("SYST", 215);
        command("NOOP", 200);
        command("USER test", 534);
        login(); command("QUIT", 221);
    }
    @Test public void tlsLoginListingsUploadDownload() throws Exception {
        login();
        assertEquals(0, download("LIST").length);
        command("TYPE I", 200);
        byte[] payload = new byte[]{0, 1, 10, 13, (byte) 255};
        try (SSLSocket data = passive()) {
            command("STOR payload.bin", 150); data.startHandshake();
            data.getOutputStream().write(payload);
        }
        reply(226);
        assertArrayEquals(payload, Files.readAllBytes(new File(home, "payload.bin").toPath()));
        extendedPassive = true;
        for (String cmd : new String[]{"LIST", "NLST", "MLSD"}) {
            assertTrue(new String(download(cmd), StandardCharsets.UTF_8).contains("payload.bin"));
        }
        assertArrayEquals(payload, download("RETR payload.bin"));
        command("PROT C", 534); command("QUIT", 221);
    }
    @Test public void missingDataProtectionDoesNotTouchFiles() throws Exception {
        byte[] original = new byte[]{42, 43};
        File target = new File(home, "existing.bin");
        Files.write(target.toPath(), original);
        secureLogin();
        for (String cmd : new String[]{"LIST", "NLST", "MLSD", "RETR existing.bin", "STOR existing.bin"}) {
            command(cmd, 534);
        }
        assertArrayEquals(original, Files.readAllBytes(target.toPath()));
        command("PBSZ 0", 200); command("PROT P", 200);
        assertArrayEquals(original, download("RETR existing.bin"));
    }

    @Test public void failedDataHandshakeAllowsNextTransfer() throws Exception {
        login();
        try (Socket broken = new Socket("127.0.0.1", passivePort())) {
            command("LIST", 150);
            broken.getOutputStream().write("not a TLS record\r\n".getBytes(StandardCharsets.US_ASCII));
            broken.getOutputStream().flush();
            reply(425);
        }
        command("NOOP", 200);
        assertEquals(0, download("LIST").length);
    }

    @Test public void activeDataConnectionUsesServerTlsRole() throws Exception {
        login();
        Files.write(new File(home, "active.bin").toPath(), new byte[]{7});
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            int port = listener.getLocalPort();
            command(extendedActive ? "EPRT |1|127.0.0.1|" + port + "|"
                    : "PORT 127,0,0,1," + (port / 256) + "," + (port % 256), 200);
            command("RETR active.bin", 150);
            try (Socket accepted = listener.accept();
                 SSLSocket data = (SSLSocket) client.getSocketFactory().createSocket(accepted, "localhost", port, true)) {
                // FTP server opens TCP, but remains the TLS server (RFC 4217).
                data.setUseClientMode(true); data.setEnabledProtocols(new String[]{"TLSv1.2"});
                data.setSoTimeout(5000); data.startHandshake();
                assertEquals(7, data.getInputStream().read());
                assertEquals(-1, data.getInputStream().read());
            }
            reply(226);
        }
    }
    @Test public void probesDoNotPermitUnauthenticatedFileAccess() throws Exception {
        for (String cmd : new String[]{"LIST", "MLST", "CWD /", "RETR missing", "STOR unexpected", "DELE missing", "MKD unexpected"}) {
            command(cmd, 530);
        }
        assertEquals(0, home.list().length);
        login();
        // Repeated AUTH and attempts to drop encryption must not break the live TLS channel.
        command("AUTH TLS", 534); command("CCC", 534); command("PROT C", 534);
        assertEquals(0, download("LIST").length);
    }

    @Test public void plainFtpStillSupportsLoginAndTransfer() throws Exception {
        stop(); start(false);
        command("FEAT", 211); command("SYST", 215); command("NOOP", 200);
        command("LIST", 530); command("STOR unexpected", 530);
        command("USER test", 331); command("PASS test", 230); command("TYPE I", 200);
        byte[] payload = new byte[]{0, 42, 10, (byte) 255};
        try (Socket data = new Socket("127.0.0.1", passivePort())) {
            command("STOR plain.bin", 150); data.getOutputStream().write(payload);
        }
        reply(226);
        assertArrayEquals(payload, Files.readAllBytes(new File(home, "plain.bin").toPath()));
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        try (Socket data = new Socket("127.0.0.1", passivePort())) {
            data.setSoTimeout(5000); command("RETR plain.bin", 150);
            int next; while ((next = data.getInputStream().read()) != -1) received.write(next);
        }
        reply(226); assertArrayEquals(payload, received.toByteArray()); command("QUIT", 221);
    }
    @Test public void extendedActiveModeSharesTheSameDataContext() throws Exception {
        extendedActive = true;
        activeDataConnectionUsesServerTlsRole();
    }

    @Test public void tls12SessionIsReusedOnNextDataConnection() throws Exception {
        login();
        byte[] previous = null;
        for (int i = 0; i < 2; i++) {
            try (SSLSocket data = passive()) {
                command("LIST", 150); data.startHandshake();
                assertEquals("TLSv1.2", data.getSession().getProtocol());
                byte[] id = data.getSession().getId();
                assertTrue(id.length > 0);
                if (previous != null) assertArrayEquals("TLS context/cache must survive across data sockets", previous, id);
                previous = id;
                assertEquals(-1, data.getInputStream().read());
            }
            reply(226);
        }
    }

    @Test public void asciiAndCompressedTransfersRetainTheirSemantics() throws Exception {
        login(); command("TYPE A", 200); command("MODE Z", 200);
        byte[] wire = "first\r\nsecond\r\n".getBytes(StandardCharsets.US_ASCII);
        try (SSLSocket data = passive()) {
            command("STOR ascii.txt", 150); data.startHandshake();
            try (java.util.zip.DeflaterOutputStream zip = new java.util.zip.DeflaterOutputStream(data.getOutputStream())) {
                zip.write(wire);
            }
        }
        reply(226);
        assertArrayEquals("first\nsecond\n".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(new File(home, "ascii.txt").toPath()));
        byte[] compressed = download("RETR ascii.txt");
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        try (InputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(compressed))) {
            int b; while ((b = in.read()) >= 0) decoded.write(b);
        }
        assertArrayEquals(wire, decoded.toByteArray());
    }

    @Test public void overwriteResumeAndAppendUseTheSelectedFilesystemAndConnection() throws Exception {
        login(); command("TYPE I", 200);
        File target = new File(home, "resume.bin");
        Files.write(target.toPath(), new byte[]{0,1,2,3,4,5,6,7});
        try (SSLSocket data = passive()) {
            command("STOR resume.bin", 150); data.startHandshake();
            data.getOutputStream().write(new byte[]{10,11,12,13,14,15});
        }
        reply(226);
        try (SSLSocket data = passive()) {
            command("REST 3", 350); command("STOR resume.bin", 150); data.startHandshake();
            data.getOutputStream().write(new byte[]{20,21});
        }
        reply(226);
        assertArrayEquals(new byte[]{10,11,12,20,21}, Files.readAllBytes(target.toPath()));
        try (SSLSocket data = passive()) {
            command("APPE resume.bin", 150); data.startHandshake();
            data.getOutputStream().write(30);
        }
        reply(226);
        assertArrayEquals(new byte[]{10,11,12,20,21,30}, download("RETR resume.bin"));
    }

}