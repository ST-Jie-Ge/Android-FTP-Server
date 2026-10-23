package com.github.stjiege.ftpshare;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.stjiege.ftpshare.services.FtpsCommandGuard;
import com.github.stjiege.ftpshare.services.FtpsConfiguration;
import org.apache.ftpserver.*;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FtpsIntegrationTest {
    private Socket control;
    private BufferedReader input;
    private BufferedWriter output;
    private void streams(Socket socket) throws Exception {
        control=socket; socket.setSoTimeout(15000);
        input=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));
        output=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),"UTF-8"));
    }
    private String reply(int expected) throws Exception {
        String line=input.readLine(); assertNotNull("Disconnected",line);
        assertTrue(line,line.startsWith(Integer.toString(expected))); return line;
    }
    private String command(String command,int expected) throws Exception {
        output.write(command+"\r\n"); output.flush(); return reply(expected);
    }
    @Test public void tlsLoginPrivateUploadDownloadAndIdentityReuse() throws Exception {
        // 身份是进程内软件密钥（见 FtpsIdentity），私钥不在 AndroidKeyStore 里，
        // 所以「身份复用」核对的是同一份持久化证书在两次构造之间不变。
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        FtpsConfiguration ssl=new FtpsConfiguration(context);
        byte[] identity=ssl.certificate().getEncoded();
        assertArrayEquals(identity,new FtpsConfiguration(context).certificate().getEncoded());
        File root=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),"ftps-integration");
        assertTrue(root.isDirectory() || root.mkdir());
        File payload=new File(root,"payload.txt");
        FtpServerFactory factory=new FtpServerFactory();
        BaseUser user=new BaseUser();user.setName("test");user.setPassword("test");user.setHomeDirectory(root.getAbsolutePath());
        user.setAuthorities(Collections.singletonList(new WritePermission()));factory.getUserManager().save(user);
        int port;try(ServerSocket reservation=new ServerSocket(0)){port=reservation.getLocalPort();}
        ListenerFactory listener=new ListenerFactory();listener.setServerAddress("127.0.0.1");listener.setPort(port);
        listener.setSslConfiguration(ssl);listener.setImplicitSsl(false);
        DataConnectionConfigurationFactory data=new DataConnectionConfigurationFactory();
        data.setPassiveAddress("127.0.0.1");data.setSslConfiguration(ssl);
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());
        factory.addListener("default",listener.createListener());FtpsCommandGuard.install(factory);
        FtpServer server=factory.createServer();
        // Test-only trust: real clients must explicitly accept the generated certificate.
        SSLContext client=SSLContext.getInstance("TLSv1.2");
        client.init(null,new TrustManager[]{new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a){}
            public void checkServerTrusted(X509Certificate[] c,String a){}
        }},null);
        try {
            server.start();streams(new Socket("127.0.0.1",port));reply(220);
            command("USER test",534);command("PASS test",534);
            command("AUTH TLS",234);
            SSLSocket secured=(SSLSocket)client.getSocketFactory().createSocket(control,"127.0.0.1",port,true);
            secured.setEnabledProtocols(new String[]{"TLSv1.2"});secured.setSoTimeout(15000);secured.startHandshake();streams(secured);
            assertArrayEquals(identity,secured.getSession().getPeerCertificates()[0].getEncoded());
            command("USER test",331);command("PASS test",230);
            command("STOR payload.txt",534);assertFalse(payload.exists());
            command("PBSZ 0",200);command("PROT C",534);command("PROT P",200);command("CCC",534);
            try(SSLSocket channel=passive(client)) {
                command("STOR payload.txt",150);channel.startHandshake();
                channel.getOutputStream().write("encrypted payload".getBytes("UTF-8"));
            }
            reply(226);
            try(SSLSocket channel=passive(client)) {
                command("RETR payload.txt",150);channel.startHandshake();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[128];int n;
                while((n=channel.getInputStream().read(b))!=-1)bytes.write(b,0,n);
                assertEquals("encrypted payload",bytes.toString("UTF-8"));
            }
            reply(226);command("QUIT",221);
        } finally {if(control!=null)control.close();server.stop();payload.delete();root.delete();}
    }
    private SSLSocket passive(SSLContext client) throws Exception {
        String response=command("PASV",227);
        String[] values=response.substring(response.indexOf('(')+1,response.indexOf(')')).split(",");
        int port=Integer.parseInt(values[4])*256+Integer.parseInt(values[5]);
        SSLSocket socket=(SSLSocket)client.getSocketFactory().createSocket("127.0.0.1",port);
        socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setSoTimeout(15000);return socket;
    }
}
