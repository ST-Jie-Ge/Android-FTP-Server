package org.apache.ftpserver.util;

/** Comparison without modulo or an early return at the first differing character. */
public final class PasswordUtil {
    private PasswordUtil() {}

    public static boolean secureCompareFast(String input, String password) {
        if (input == null || password == null) return false;
        return secureCompare(input, password, Math.max(input.length(), password.length()));
    }

    public static boolean secureCompare(String input, String password, int loops) {
        if (input == null || password == null) return false;
        if (loops < Math.max(input.length(), password.length())) {
            throw new IllegalArgumentException("loops must cover both strings");
        }
        int difference = input.length() ^ password.length();
        for (int i = 0; i < loops; i++) {
            char left = i < input.length() ? input.charAt(i) : 0;
            char right = i < password.length() ? password.charAt(i) : 0;
            difference |= left ^ right;
        }
        return difference == 0;
    }
}
