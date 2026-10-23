package org.apache.ftpserver.util;

import java.util.Locale;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.Md5PasswordEncryptor;
import org.apache.ftpserver.usermanager.SaltedPasswordEncryptor;
import org.junit.Test;
import static org.junit.Assert.*;

public class PasswordUtilTest {
    @Test public void emptyAndNullInputsAreSafe() {
        assertTrue(PasswordUtil.secureCompareFast("", ""));
        assertFalse(PasswordUtil.secureCompareFast("", "secret"));
        assertFalse(PasswordUtil.secureCompareFast("secret", ""));
        assertFalse(PasswordUtil.secureCompareFast(null, "secret"));
        assertFalse(PasswordUtil.secureCompareFast("secret", null));
        assertFalse(PasswordUtil.secureCompareFast(null, null));
        assertTrue(PasswordUtil.secureCompare("", "", 1024));
    }
    @Test public void comparisonChecksEveryPositionAndLength() {
        assertTrue(PasswordUtil.secureCompareFast("密码\u0000", "密码\u0000"));
        assertFalse(PasswordUtil.secureCompareFast("a", "aa"));
        assertFalse(PasswordUtil.secureCompareFast("aa", "a"));
        assertFalse(PasswordUtil.secureCompareFast("secret", "secrex"));
        assertFalse(PasswordUtil.secureCompareFast("", "\u0000"));
    }
    @Test(expected = IllegalArgumentException.class)
    public void rejectsInsufficientLoopBudget() {
        PasswordUtil.secureCompare("long input", "short", 5);
    }
    @Test public void clearTextComparatorHandlesEmptyPasswords() {
        ClearTextPasswordEncryptor encryptor = new ClearTextPasswordEncryptor();
        assertTrue(encryptor.matches("", ""));
        assertFalse(encryptor.matches("", "secret"));
        assertFalse(encryptor.matches("secret", ""));
        assertFalse(encryptor.matches(null, "secret"));
        assertFalse(encryptor.matches("secret", null));
    }
    @Test public void defaultMd5FormatRemainsCompatible() {
        Md5PasswordEncryptor encryptor = new Md5PasswordEncryptor();
        assertEquals("5F4DCC3B5AA765D61D8327DEB882CF99", encryptor.encrypt("password"));
        assertTrue(encryptor.matches("password", encryptor.encrypt("password").toUpperCase(Locale.ROOT)));
        assertTrue(encryptor.matches("password", encryptor.encrypt("password").toLowerCase(Locale.ROOT)));
        assertTrue(encryptor.matches("", encryptor.encrypt("")));
        assertFalse(encryptor.matches("", encryptor.encrypt("password")));
        assertFalse(encryptor.matches(null, encryptor.encrypt("password")));
        assertFalse(encryptor.matches("password", ""));
        assertFalse(encryptor.matches("password", null));
    }
    @Test public void saltedFormatAndInvalidRecordsAreSafe() {
        SaltedPasswordEncryptor encryptor = new SaltedPasswordEncryptor();
        String stored = encryptor.encrypt("密码");
        assertTrue(encryptor.matches("密码", stored));
        assertFalse(encryptor.matches("", stored));
        assertTrue(encryptor.matches("", encryptor.encrypt("")));
        assertFalse(encryptor.matches("anything", ""));
        assertFalse(encryptor.matches("anything", "invalid"));
        assertFalse(encryptor.matches(null, stored));
        assertFalse(encryptor.matches("密码", null));
    }
}
