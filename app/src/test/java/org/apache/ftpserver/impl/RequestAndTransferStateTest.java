package org.apache.ftpserver.impl;

import org.apache.mina.core.session.DummySession;
import org.junit.Test;

import static org.junit.Assert.*;

public class RequestAndTransferStateTest {
    @Test public void pathQuotesAreRemovedButCredentialsRemainOpaque() {
        assertEquals("folder with spaces/file #1.txt",
                new DefaultFtpRequest("RETR \"folder with spaces/file #1.txt\"").getArgument());
        assertEquals("\"p@ss word\"",
                new DefaultFtpRequest("PASS \"p@ss word\"").getArgument());
        assertEquals("name ", new DefaultFtpRequest("RETR \"name \"").getArgument());
        assertEquals("name \"quoted\".txt",
                new DefaultFtpRequest("STOR \"name \"\"quoted\"\".txt\"").getArgument());
    }

    @Test public void transferStateIsExplicitAndCleared() {
        FtpIoSession session = new FtpIoSession(new DummySession(), null);
        assertFalse(session.isTransferActive());
        session.setTransferActive(true);
        assertTrue(session.isTransferActive());
        session.setTransferActive(false);
        assertFalse(session.isTransferActive());
    }
}
