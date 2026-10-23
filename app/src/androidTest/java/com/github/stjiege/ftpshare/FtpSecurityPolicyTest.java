package com.github.stjiege.ftpshare;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.services.FtpService;
import com.github.stjiege.ftpshare.services.IpWhitelistConfigurator;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ipfilter.RemoteIpFilter;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.mina.filter.firewall.Subnet;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FtpSecurityPolicyTest {
    @Test public void storedBroadAndMalformedWhitelistRulesFailClosed() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        SharedPreferences settings = context.getSharedPreferences("security-whitelist-test", Context.MODE_PRIVATE);
        settings.edit().clear()
                .putBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, true)
                .putStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES, new HashSet<>(Arrays.asList(
                        "192.168.1.5/0|broad", "192.168.1.5/33|invalid",
                        "localhost|hostname", "127.0.0.1.|trailing dot",
                        "127.0.0.1|valid")))
                .commit();
        try {
            ListenerFactory listener = new ListenerFactory();
            IpWhitelistConfigurator.apply(listener, settings);
            RemoteIpFilter filter = (RemoteIpFilter) listener.getSessionFilter();
            assertNotNull(filter);
            assertEquals(1, filter.size());
            Subnet allowed = filter.iterator().next();
            assertTrue(allowed.inSubnet(InetAddress.getByName("127.0.0.1")));
            assertFalse(allowed.inSubnet(InetAddress.getByName("192.168.1.5")));
        } finally {
            settings.edit().clear().commit();
        }
    }

    @Test public void whitelistCannotChangeWhileServiceIsStarting() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences settings = context.getSharedPreferences(Constants.PreferenceConsts.FILE_NAME, Context.MODE_PRIVATE);
        Field state = FtpService.class.getDeclaredField("lifecycleState");
        state.setAccessible(true);
        Object oldState = state.get(null);
        Field queuedStart = FtpService.class.getDeclaredField("pendingStartContext");
        queuedStart.setAccessible(true);
        Object oldQueuedStart = queuedStart.get(null);
        boolean wasEnabled = settings.getBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, false);
        Set<String> oldEntries = settings.getStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES, null);
        try {
            assertTrue(FtpService.isFTPServiceFullyStopped());
            Object starting = Enum.valueOf((Class) oldState.getClass(), "STARTING");
            state.set(null, starting);
            assertFalse(FtpService.updateIpWhitelistEnabled(context, !wasEnabled));
            assertFalse(FtpService.updateIpWhitelistEntries(context,
                    new HashSet<>(Arrays.asList("192.168.1.5|test"))));
            assertEquals(wasEnabled, settings.getBoolean(Constants.PreferenceConsts.IP_WHITELIST_ENABLED, false));
            assertEquals(oldEntries, settings.getStringSet(Constants.PreferenceConsts.IP_WHITELIST_ENTRIES, null));
            state.set(null, oldState);
            queuedStart.set(null, context);
            assertFalse(FtpService.isFTPServiceFullyStopped());
            assertFalse(FtpService.updateIpWhitelistEnabled(context, !wasEnabled));
            assertFalse(FtpService.updateIpWhitelistEntries(context,
                    new HashSet<>(Arrays.asList("192.168.1.5|test"))));
        } finally {
            state.set(null, oldState);
            queuedStart.set(null, oldQueuedStart);
        }
    }

    @Test public void passiveTransferWorksAndActiveCommandsAreDisabled() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File home = new File(context.getCacheDir(), "ftp-security-test");
        assertTrue(home.isDirectory() || home.mkdir());
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        FtpServerFactory factory = new FtpServerFactory();
        BaseUser user = new BaseUser();
        user.setName("security-test");
        user.setPassword("security-test");
        user.setHomeDirectory(home.getAbsolutePath());
        factory.getUserManager().save(user);
        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("127.0.0.1");
        listener.setPort(port);
        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1");
        data.setPassiveIpCheck(true);
        data.setActiveEnabled(false);
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        factory.addListener("default", listener.createListener());
        FtpServer server = factory.createServer();
        try {
            server.start();
            try (Socket control = new Socket("127.0.0.1", port)) {
                control.setSoTimeout(10000);
                BufferedReader input = new BufferedReader(new InputStreamReader(control.getInputStream(), "UTF-8"));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(control.getOutputStream(), "UTF-8"));
                assertCode(input, 220);
                command(input, output, "USER security-test", 331);
                command(input, output, "PASS security-test", 230);
                command(input, output, "PORT 127,0,0,1,12,34", 501);
                command(input, output, "EPRT |1|127.0.0.1|1234|", 501);
                String passive = command(input, output, "PASV", 227);
                String[] address = passive.substring(passive.indexOf('(') + 1, passive.indexOf(')')).split(",");
                int dataPort = Integer.parseInt(address[4]) * 256 + Integer.parseInt(address[5]);
                try (Socket channel = new Socket("127.0.0.1", dataPort)) {
                    channel.setSoTimeout(10000);
                    command(input, output, "LIST", 150);
                    while (channel.getInputStream().read() != -1) { }
                    assertCode(input, 226);
                }
                String guardedPassive = command(input, output, "PASV", 227);
                String[] guardedAddress = guardedPassive.substring(
                        guardedPassive.indexOf('(') + 1, guardedPassive.indexOf(')')).split(",");
                int guardedPort = Integer.parseInt(guardedAddress[4]) * 256
                        + Integer.parseInt(guardedAddress[5]);
                try (Socket outsider = new Socket()) {
                    outsider.bind(new InetSocketAddress("127.0.0.2", 0));
                    outsider.connect(new InetSocketAddress("127.0.0.1", guardedPort));
                    outsider.setSoTimeout(10000);
                    assertEquals("127.0.0.2", outsider.getLocalAddress().getHostAddress());
                    command(input, output, "LIST", 150);
                    String rejected = input.readLine();
                    assertNotNull("Mismatched data peer must fail the transfer", rejected);
                    assertTrue(rejected, rejected.startsWith("551"));
                    assertEquals("Rejected data socket must close without a listing", -1,
                            outsider.getInputStream().read());
                }
            }
        } finally {
            server.stop();
            home.delete();
        }
    }

    private static String assertCode(BufferedReader input, int code) throws Exception {
        String line = input.readLine();
        assertNotNull("Control connection closed", line);
        assertTrue(line, line.startsWith(Integer.toString(code)));
        return line;
    }

    private static String command(BufferedReader input, BufferedWriter output,
                                  String request, int code) throws Exception {
        output.write(request + "\r\n");
        output.flush();
        return assertCode(input, code);
    }
}
