package com.github.stjiege.ftpshare.utils;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts locally stored FTP passwords with a non-exportable Android Keystore key. */
public final class PasswordCipher {
    private static final String PREFIX = "g2:";
    private static final String KEY_ALIAS = "ftp_server_g2_account_key";

    private PasswordCipher() { }

    public static String encrypt(String plainText) throws Exception {
        if (plainText == null || plainText.length() == 0) return plainText == null ? "" : plainText;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Android Keystore rejects caller-provided IVs for randomized encryption.
        // Let the provider create a fresh IV, then persist it with the ciphertext.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        if (iv == null || iv.length != 12) {
            throw new IllegalStateException("Android Keystore returned an invalid GCM IV");
        }
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP);
    }

    /** Plaintext values from older versions remain readable and migrate on their next save. */
    public static String decrypt(String storedValue) throws Exception {
        if (storedValue == null || !storedValue.startsWith(PREFIX)) return storedValue == null ? "" : storedValue;
        byte[] payload = Base64.decode(storedValue.substring(PREFIX.length()), Base64.NO_WRAP);
        if (payload.length <= 12) throw new IllegalArgumentException("Invalid encrypted password");
        byte[] iv = new byte[12];
        byte[] encrypted = new byte[payload.length - 12];
        System.arraycopy(payload, 0, iv, 0, iv.length);
        System.arraycopy(payload, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}

