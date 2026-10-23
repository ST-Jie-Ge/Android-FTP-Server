package com.github.stjiege.ftpshare.filesystem;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.Constants;
import com.github.stjiege.ftpshare.data.AccountItem;
import com.github.stjiege.ftpshare.services.AccountRegistrar;
import com.github.stjiege.ftpshare.services.FtpConnectionConfig;
import com.github.stjiege.ftpshare.services.TransferCommandGuard;
import com.github.stjiege.ftpshare.utils.FtpLogFtplet;
import org.apache.ftpserver.*;
import org.apache.ftpserver.ftplet.FileSystemFactory;
import org.apache.ftpserver.impl.DefaultFtpHandler;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.*;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real Android sockets and production account/command/file code; only roots/ports are isolated. */
@RunWith(AndroidJUnit4.class)
public class FtpWorkflowVerificationTest {
    @Rule public TestName testName = new TestName();
    private Context context;
    private PrintWriter transcript;
    private int clientId;
    @Before public void begin() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File reports = new File(context.getFilesDir(), "ftp-verification");
        assertTrue(reports.isDirectory() || reports.mkdir());
        transcript = new PrintWriter(new OutputStreamWriter(new FileOutputStream(
                new File(reports, testName.getMethodName() + ".log")), "UTF-8"), true);
        note("TEST " + testName.getMethodName() + " " + new Date());
        note("Android " + android.os.Build.VERSION.RELEASE + " API " + android.os.Build.VERSION.SDK_INT);
    }
    @After public void end() { note("END (verdict is in instrumentation results)"); transcript.close(); }
    private synchronized void note(String text) { transcript.println(System.currentTimeMillis() + " " + text); }

    private File directory(File parent) throws Exception {
        File root = File.createTempFile("ftp-workflow-", ".dir", parent);
        assertTrue(root.delete()); assertTrue(root.mkdir()); return root.getCanonicalFile();
    }
    private void cleanOwnedTree(File root, File child) throws Exception {
        String base = root.getCanonicalPath(), path = child.getCanonicalPath();
        if (!path.equals(base) && !path.startsWith(base + File.separator)) throw new IOException("Outside test root");
        File[] children = child.listFiles();
        if (children != null) for (File entry : children) cleanOwnedTree(root, entry);
        if (child.exists() && !child.delete()) throw new IOException("Test cleanup failed: " + child);
    }
    private void write(File f, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(value.getBytes("UTF-8")); }
    }
    private String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) { return new String(bytes(in), "UTF-8"); }
    }
    private byte[] bytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
        return out.toByteArray();
    }
    private final class Fixture implements AutoCloseable {
        final File root, other;
        final FtpServer server;
        final ExecutorService transfers = Executors.newFixedThreadPool(10);
        final SharedPreferences settings;
        final int port;
        Fixture(boolean anonymousWritable) throws Exception {
            root = directory(context.getCacheDir());
            other = directory(context.getExternalCacheDir());
            settings = context.getSharedPreferences("ftp-workflow-isolated", Context.MODE_PRIVATE);
            settings.edit().clear().putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE, true)
                    .putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_WRITABLE, anonymousWritable)
                    .putString(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH, root.getAbsolutePath())
                    .putBoolean(Constants.PreferenceConsts.ANONYMOUS_MODE_PATH_USER_SELECTED, true).commit();
            AccountItem named = new AccountItem(); named.account = "workflow";
            named.password = "test-only-password"; named.path = root.getAbsolutePath();
            FtpServerFactory factory = new FtpServerFactory();
            AccountRegistrar.registerAccounts(factory, Collections.singletonList(named), context, settings);
            FileSystemFactory normal = factory.getFileSystem();
            BaseUser multi = new BaseUser(); multi.setName("multi"); multi.setPassword("test-only-password");
            multi.setHomeDirectory(root.getAbsolutePath());
            multi.setAuthorities(Collections.singletonList(new WritePermission()));
            factory.getUserManager().save(multi);
            // Expose only disposable roots, but use the real MultiRoot implementation.
            factory.setFileSystem(user -> {
                if (!"multi".equals(user.getName())) return normal.createFileSystemView(user);
                LinkedHashMap<String, File> roots = new LinkedHashMap<>();
                roots.put("device", root); roots.put("sdcard", other);
                return new MultiRootFileSystemView(user, roots, false);
            });
            factory.setFileSystem(new SafeUploadFileSystemFactory(factory.getFileSystem()));
            TransferCommandGuard.install(factory);
            factory.getFtplets().put("audit", new FtpLogFtplet());
            FtpConnectionConfig config = new FtpConnectionConfig();
            config.setAnonymousEnabled(true); config.setMaxAnonymousLogins(4); config.setMaxLogins(12);
            factory.setConnectionConfig(config);
            try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
            ListenerFactory listener = new ListenerFactory(); listener.setServerAddress("127.0.0.1");
            listener.setPort(port); listener.setIdleTimeout(0);
            DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
            data.setPassiveAddress("127.0.0.1"); data.setActiveEnabled(false); data.setIdleTime(10);
            listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
            factory.addListener("default", listener.createListener());
            TransferSlots.configure(6); DefaultFtpHandler.setTransferExecutor(transfers);
            DefaultFtpHandler.setCopyExecutor(CopyExecutor.get());
            server = factory.createServer(); server.start();
            note("SERVER loopback:" + port + " anonymousWritable=" + anonymousWritable);
            note("ROOT " + root + " dev=" + Os.stat(root.getPath()).st_dev);
            note("OTHER " + other + " dev=" + Os.stat(other.getPath()).st_dev);
        }
        Client client() throws Exception { return new Client(port); }
        @Override public void close() throws Exception {
            server.stop(); transfers.shutdownNow(); transfers.awaitTermination(10, TimeUnit.SECONDS);
            DefaultFtpHandler.setTransferExecutor(null); DefaultFtpHandler.setCopyExecutor(null);
            settings.edit().clear().commit();
            cleanOwnedTree(root, root); cleanOwnedTree(other, other);
        }
    }
    private final class Client implements AutoCloseable {
        final Socket socket;
        final BufferedReader input;
        final BufferedWriter output;
        final String id = "C" + (++clientId);
        Client(int port) throws Exception {
            socket = new Socket("127.0.0.1", port); socket.setSoTimeout(15000);
            input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
            reply(220);
        }
        void send(String command) throws Exception {
            note(id + " > " + (command.startsWith("PASS ") ? "PASS <test password redacted>" : command));
            output.write(command + "\r\n"); output.flush();
        }
        String reply(int... expected) throws Exception {
            String first = input.readLine(); note(id + " < " + first); assertNotNull("Unexpected EOF", first);
            int code = Integer.parseInt(first.substring(0, 3));
            if (first.length() > 3 && first.charAt(3) == '-') {
                String line;
                do { line = input.readLine(); note(id + " < " + line); assertNotNull(line); }
                while (!line.startsWith(code + " "));
            }
            boolean ok = false; for (int candidate : expected) ok |= code == candidate;
            assertTrue("Expected " + Arrays.toString(expected) + " but got " + first, ok); return first;
        }
        String command(String cmd, int... codes) throws Exception { send(cmd); return reply(codes); }
        void login(String user) throws Exception {
            command("USER " + user, 331);
            command("PASS " + (user.equals("anonymous") ? "guest@example.invalid" : "test-only-password"), 230);
            command("TYPE I", 200);
        }
        Socket passive() throws Exception {
            String reply = command("PASV", 227);
            String[] a = reply.substring(reply.indexOf('(') + 1, reply.indexOf(')')).split(",");
            Socket data = new Socket("127.0.0.1", Integer.parseInt(a[4]) * 256 + Integer.parseInt(a[5]));
            data.setSoTimeout(15000); return data;
        }
        String list(String path) throws Exception {
            String listing;
            try (Socket data = passive()) {
                command("LIST " + path, 150); listing = new String(bytes(data.getInputStream()), "UTF-8");
            }
            reply(226); note(id + " DATA LIST " + listing.replace("\r\n", " | ")); return listing;
        }
        void upload(String path, String value) throws Exception {
            try (Socket data = passive()) {
                command("STOR " + path, 150); data.getOutputStream().write(value.getBytes("UTF-8"));
            }
            reply(226); note(id + " DATA uploaded UTF-8 bytes=" + value.getBytes("UTF-8").length);
        }
        String download(String path) throws Exception {
            String value;
            try (Socket data = passive()) {
                command("RETR " + path, 150); value = new String(bytes(data.getInputStream()), "UTF-8");
            }
            reply(226); return value;
        }
        @Override public void close() throws Exception { socket.close(); }
    }

    @Test public void anonymousAndNamedLoginListUploadAndMoveConflict() throws Exception {
        try (Fixture f = new Fixture(true)) {
            for (String user : new String[]{"anonymous", "workflow", "multi"}) {
                String prefix = user.equals("multi") ? "/device/" : "/";
                try (Client c = f.client()) {
                    c.login(user);
                    String a = user + "-源.txt", b = user + "-目标.txt";
                    c.upload(prefix + a, "source:" + user); c.upload(prefix + b, "existing:" + user);
                    assertEquals("source:" + user, c.download(prefix + a));
                    assertTrue(c.list(prefix).contains(a));
                    c.command("RNFR " + prefix + a, 350); c.command("RNTO " + prefix + b, 553);
                    assertEquals("source:" + user, read(new File(f.root, a)));
                    assertEquals("existing:" + user, read(new File(f.root, b)));
                    c.command("MKD " + prefix + user + "-dir", 257);
                    c.command("RNFR " + prefix + a, 350);
                    c.command("RNTO " + prefix + user + "-dir/moved.txt", 250);
                    assertEquals("source:" + user, c.download(prefix + user + "-dir/moved.txt"));
                    c.upload(prefix + b, "explicit STOR overwrite");
                    assertEquals("explicit STOR overwrite", read(new File(f.root, b)));
                    c.command("QUIT", 221); note("PASS assertions for " + user);
                }
            }
        }
    }
    @Test public void anonymousReadonlyAndMalformedLoginResponses() throws Exception {
        try (Fixture f = new Fixture(false)) {
            write(new File(f.root, "visible.txt"), "readable");
            // Upstream USER closes after a missing argument; test each invalid request on a fresh socket.
            for (String invalid : new String[]{"USER", "USER    "}) {
                try (Client c = f.client()) { c.command(invalid, 501); }
            }
            try (Client c = f.client()) {
                c.command("USER unknown-user", 331); c.command("PASS wrong", 530);
                c.command("QUIT", 221);
            }
            try (Client c = f.client()) {
                c.login("anonymous"); assertTrue(c.list("/").contains("visible.txt"));
                try (Socket data = c.passive()) { c.command("STOR /denied.txt", 550, 553); }
                assertFalse(new File(f.root, "denied.txt").exists());
                c.command("RNFR /visible.txt", 350);
                c.command("RNTO /denied.txt", 553); c.command("QUIT", 221);
            }
            note("PASS malformed USER rejected; valid anonymous login/list succeeds; readonly writes rejected");
        }
    }
    @Test public void cancelUploadBeforeFirstBytePreservesExistingFile() throws Exception {
        try (Fixture f = new Fixture(true)) {
            for (String user : new String[]{"anonymous", "workflow", "multi"}) {
                String path = user.equals("multi") ? "/device/keep.txt" : "/keep.txt";
                write(new File(f.root, "keep.txt"), "KEEP-ORIGINAL");
                try (Client c = f.client()) {
                    c.login(user);
                    try (Socket data = c.passive()) {
                        c.command("STOR " + path, 150); c.send("ABOR");
                        c.reply(426, 451, 551); c.reply(226);
                    }
                    assertEquals("Original lost for " + user, "KEEP-ORIGINAL", read(new File(f.root, "keep.txt")));
                    c.command("NOOP", 200); c.command("QUIT", 221);
                    note("PASS zero-byte cancelled upload retained original for " + user);
                }
            }
        }
    }
    @Test public void crossVolumeMoveAndConflict() throws Exception {
        try (Fixture f = new Fixture(true); Client c = f.client()) {
            assertNotEquals("Test must cross actual mount boundaries", Os.stat(f.root.getPath()).st_dev,
                    Os.stat(f.other.getPath()).st_dev);
            c.login("multi"); c.upload("/device/source.txt", "cross-volume-payload");
            c.command("RNFR /device/source.txt", 350); c.command("RNTO /sdcard/moved.txt", 250);
            assertFalse(new File(f.root, "source.txt").exists());
            assertEquals("cross-volume-payload", read(new File(f.other, "moved.txt")));
            c.upload("/device/source.txt", "second-source");
            c.command("RNFR /device/source.txt", 350); c.command("RNTO /sdcard/moved.txt", 553);
            assertEquals("second-source", read(new File(f.root, "source.txt")));
            assertEquals("cross-volume-payload", read(new File(f.other, "moved.txt")));
            c.command("QUIT", 221); note("PASS real cross-device move + collision preservation");
        }
    }
    @Test public void aborCancelsRunningCrossVolumeCopy() throws Exception { cancelCopy(false); }
    @Test public void disconnectCancelsRunningCrossVolumeCopy() throws Exception { cancelCopy(true); }
    private void cancelCopy(boolean disconnect) throws Exception {
        try (Fixture f = new Fixture(true); Client c = f.client()) {
            assertNotEquals(Os.stat(f.root.getPath()).st_dev, Os.stat(f.other.getPath()).st_dev);
            File source = new File(f.root, "large.bin");
            try (RandomAccessFile out = new RandomAccessFile(source, "rw")) { out.setLength(512L * 1024 * 1024); }
            c.login("multi"); c.command("RNFR /device/large.bin", 350); c.send("RNTO /sdcard/large.bin");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean observed = false;
            while (System.nanoTime() < deadline && !observed) {
                File[] temps = f.other.listFiles((dir, name) -> name.startsWith(".ftp-moving-"));
                if (temps != null) for (File temp : temps) if (temp.length() > 0) {
                    note("OBSERVED in-progress staging bytes=" + temp.length()); observed = true;
                }
                if (!observed) Thread.sleep(2);
            }
            assertTrue("Must cancel an actually running copy", observed);
            assertEquals("Local move must not consume transfer slots", 0, TransferSlots.snapshot().inUse);
            if (disconnect) { note("CLIENT closes control socket during copy"); c.socket.close(); }
            else { c.send("ABOR"); c.reply(553); c.reply(226); c.command("NOOP", 200); }
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && f.other.list().length != 0) Thread.sleep(5);
            assertEquals(0, f.other.list().length);
            assertEquals(512L * 1024 * 1024, source.length());
            assertFalse(new File(f.other, "large.bin").exists());
            note("PASS cancelled running copy; source intact; target absent; staging cleaned");
        }
    }
}
