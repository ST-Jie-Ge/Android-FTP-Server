package org.apache.ftpserver.impl;

import java.lang.reflect.Method;
import java.net.Socket;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise option policy without relying on host kernel buffer rounding. */
public class DataSocketOptionsTest {

    private static class OptionsSocket extends Socket {
        int send, receive, sendSets, receiveSets;
        boolean noDelay;
        OptionsSocket(int send, int receive) { this.send = send; this.receive = receive; }
        @Override public boolean getTcpNoDelay() { return noDelay; }
        @Override public void setTcpNoDelay(boolean value) { noDelay = value; }
        @Override public int getSendBufferSize() { return send; }
        @Override public int getReceiveBufferSize() { return receive; }
        @Override public void setSendBufferSize(int size) { send = size; sendSets++; }
        @Override public void setReceiveBufferSize(int size) { receive = size; receiveSets++; }
    }
    private void apply(OptionsSocket socket) throws Exception {
        Method apply = IODataConnectionFactory.class.getDeclaredMethod("applyDataSocketOptions", Socket.class);
        apply.setAccessible(true);
        apply.invoke(new IODataConnectionFactory(null, null), socket);
    }
    @Test public void preservesLargerSystemBuffers() throws Exception {
        OptionsSocket socket = new OptionsSocket(1024 * 1024, 2 * 1024 * 1024);
        apply(socket);
        assertEquals(1024 * 1024, socket.send);
        assertEquals(2 * 1024 * 1024, socket.receive);
        assertEquals(0, socket.sendSets); assertEquals(0, socket.receiveSets);
        assertTrue(socket.noDelay);
    }
    @Test public void preservesSmallInitialBuffersForKernelAutotuning() throws Exception {
        OptionsSocket socket = new OptionsSocket(64 * 1024, 64 * 1024);
        apply(socket); apply(socket);
        assertEquals(64 * 1024, socket.send); assertEquals(64 * 1024, socket.receive);
        assertEquals(0, socket.sendSets); assertEquals(0, socket.receiveSets);
    }
}
