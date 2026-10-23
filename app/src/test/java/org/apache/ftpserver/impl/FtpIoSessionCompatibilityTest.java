package org.apache.ftpserver.impl;

import org.apache.mina.core.session.DummySession;
import org.apache.mina.core.session.IoSession;
import org.junit.Test;
import static org.junit.Assert.*;

public class FtpIoSessionCompatibilityTest {
    @Test public void sessionImplementsNewMinaInterface() {
        FtpIoSession wrapper = new FtpIoSession(new DummySession(), null);
        IoSession session = wrapper;
        // MINA's default DummySession is backed by a dummy acceptor.
        assertTrue(session.isServer());
        assertNull(wrapper.getClientCertificates());
    }
    @Test public void cancellationLookupDoesNotCreateAConnection() {
        FtpIoSession session = new FtpIoSession(new DummySession(), null);
        assertNull(session.peekDataConnection());
    }
}
