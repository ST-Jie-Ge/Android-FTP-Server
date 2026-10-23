package org.apache.ftpserver.impl;

import com.github.stjiege.ftpshare.services.TransferCommandGuard;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.*;
import org.apache.ftpserver.command.impl.TYPE;
import org.apache.ftpserver.message.MessageResourceFactory;
import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.future.WriteFuture;
import org.apache.mina.core.future.DefaultWriteFuture;
import org.junit.Test;
import java.io.*;
import java.net.Socket;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import static org.junit.Assert.*;

public class AsciiChainTest {
    static class Session extends FtpIoSession {
        FtpReply reply;
        Session(FtpServerContext context) { super(new DummySession(),context); }
        @Override public void logoutUser() { }
        @Override public WriteFuture write(Object value) {
            reply=(FtpReply)value;
            return DefaultWriteFuture.newWrittenFuture(this);
        }
    }
    static FtpServerContext context() {
        Object messages=new MessageResourceFactory().createMessageResource();
        return (FtpServerContext)Proxy.newProxyInstance(AsciiChainTest.class.getClassLoader(),new Class[]{FtpServerContext.class},
            (p,m,a)->m.getName().equals("getMessageResource")?messages:null);
    }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static byte[] zip(byte[] b) throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        try(DeflaterOutputStream out=new DeflaterOutputStream(result)){out.write(b);}
        return result.toByteArray();
    }
    static byte[] unzip(byte[] b) throws Exception {
        ByteArrayOutputStream result=new ByteArrayOutputStream();
        try(InputStream in=new InflaterInputStream(new ByteArrayInputStream(b))){byte[] buf=new byte[64];int n;while((n=in.read(buf))!=-1)result.write(buf,0,n);}
        return result.toByteArray();
    }
    static class DataSocket extends Socket {
        InputStream input;
        OutputStream output;
        @Override public InputStream getInputStream(){return input;}
        @Override public OutputStream getOutputStream(){return output;}
    }
    static IODataConnection connection(Session s,DataSocket socket,boolean zipped,boolean[] closed) {
        ServerDataConnectionFactory factory=(ServerDataConnectionFactory)Proxy.newProxyInstance(
            AsciiChainTest.class.getClassLoader(),new Class[]{ServerDataConnectionFactory.class},(p,m,a)->{
                if(m.getName().equals("isZipMode"))return zipped;
                if(m.getName().equals("closeDataConnection")){closed[0]=true;return null;}
                return null;
            });
        return new IODataConnection(socket,s,factory);
    }
    @Test public void typeCommandThroughUploadDownloadAndCompression() throws Exception {
        for(boolean zipped:new boolean[]{false,true}) {
            FtpServerContext c=context();Session s=new Session(c);
            for(String type:new String[]{"A","I","A N","L 8"}) {
                new TYPE().execute(s,c,new DefaultFtpRequest("TYPE "+type));assertEquals(200,s.reply.getCode());
                boolean ascii=s.getDataType()==DataType.ASCII;
                byte[] local=bytes("中文\nend\r"),wire=ascii?bytes("中文\r\nend\r\u0000"):local;
                DataSocket socket=new DataSocket();ByteArrayOutputStream network=new ByteArrayOutputStream();socket.output=network;
                long n=connection(s,socket,zipped,new boolean[1]).transferToClient(s.getFtpletSession(),new ByteArrayInputStream(local));
                assertEquals(wire.length,n);assertArrayEquals(wire,zipped?unzip(network.toByteArray()):network.toByteArray());
                socket.input=new ByteArrayInputStream(zipped?zip(wire):wire);ByteArrayOutputStream file=new ByteArrayOutputStream();
                assertEquals(wire.length,connection(s,socket,zipped,new boolean[1]).transferFromClient(s.getFtpletSession(),file));
                assertArrayEquals(local,file.toByteArray());
            }
        }
    }
    @Test public void reinResetsTypeAndAsciiSizeIs550() throws Exception {
        Session s=new Session(context());s.setDataType(DataType.BINARY);s.setFileOffset(42);s.reinitialize();
        assertEquals(DataType.ASCII,s.getDataType());assertEquals(0,s.getFileOffset());
        FtpServerFactory factory=new FtpServerFactory();TransferCommandGuard.install(factory);
        for(String name:new String[]{"SIZE","REST"}) {
            factory.getCommandFactory().getCommand(name).execute(s,context(),new DefaultFtpRequest(name+" 1"));
            assertEquals(name.equals("SIZE")?550:504,s.reply.getCode());
        }
    }
    @Test public void writeFailureClosesDataConnection() throws Exception {
        Session s=new Session(context());DataSocket socket=new DataSocket();boolean[] closed={false};
        socket.output=new OutputStream(){@Override public void write(int b)throws IOException{throw new IOException("test failure");}};
        try {connection(s,socket,false,closed).transferToClient(s.getFtpletSession(),new ByteArrayInputStream(bytes("text\n")));fail();}
        catch(IOException expected){assertTrue(closed[0]);}
    }
    @Test public void normalRunsAreWrittenInBulk() throws Exception {
        final int[] calls={0,0};OutputStream out=new OutputStream(){
            @Override public void write(int b){calls[0]++;}
            @Override public void write(byte[] b,int off,int len){calls[1]++;}
        };
        new AsciiTransfer(true).write(new byte[65536],65536,out);
        assertEquals(0,calls[0]);assertEquals(1,calls[1]);
    }
}
