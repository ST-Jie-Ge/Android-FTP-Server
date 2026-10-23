package org.apache.ftpserver.impl;

import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultFtpRequestTest {
    @Test public void whitespaceOnlyUserHasNoArgument() {
        assertNull(new DefaultFtpRequest("USER    \t").getArgument());
        assertNull(new DefaultFtpRequest("USER\t  ").getArgument());
    }
    @Test public void passwordWhitespaceRemainsOpaque() {
        assertEquals("   ", new DefaultFtpRequest("PASS    ").getArgument());
    }
}
