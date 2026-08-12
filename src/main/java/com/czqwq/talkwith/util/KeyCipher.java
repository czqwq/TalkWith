package com.czqwq.talkwith.util;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES encryption for the API key at rest.
 * <p>
 * The user enters a <b>short passphrase</b> (8–32 chars) in the settings GUI;
 * {@link PBKDF2} expands it into a 128-bit AES key, so a short passphrase yields a
 * proper-length key. Each encryption uses a fresh random salt and IV. The stored value is
 * {@code $tws$<b64 salt>:<b64 iv>:<b64 ciphertext>}, recognizable via {@link #isEncrypted}.
 * <p>
 * The passphrase itself is persisted next to the ciphertext (user chose "remember key"), so
 * this protects the API key from being read directly out of the config / world save — it is
 * encryption, not just obfuscation, but it is NOT strong security against someone who also
 * has the passphrase. {@code decrypt} throws (never silently corrupts) on a wrong passphrase,
 * which the caller reports as "re-enter the encryption key".
 */
public final class KeyCipher {

    public static final String PREFIX = "$tws$";
    private static final int ITERATIONS = 10_000;
    private static final int KEY_BITS = 128;
    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KeyCipher() {}

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Encrypts {@code plain}. Empty input stays empty (nothing to protect). */
    public static String encrypt(String plain, String passphrase) throws Exception {
        if (plain == null || plain.isEmpty()) return plain;
        if (passphrase == null || passphrase.isEmpty()) return plain;
        byte[] salt = new byte[SALT_LEN];
        RANDOM.nextBytes(salt);
        byte[] iv = new byte[IV_LEN];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new IvParameterSpec(iv));
        byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return PREFIX + Base64.getEncoder()
            .encodeToString(salt)
            + ":"
            + Base64.getEncoder()
                .encodeToString(iv)
            + ":"
            + Base64.getEncoder()
                .encodeToString(ct);
    }

    /** Decrypts {@code stored}. Legacy plaintext is returned unchanged. */
    public static String decrypt(String stored, String passphrase) throws Exception {
        if (!isEncrypted(stored)) return stored;
        String body = stored.substring(PREFIX.length());
        String[] parts = body.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Malformed encrypted key");
        byte[] salt = Base64.getDecoder()
            .decode(parts[0]);
        byte[] iv = Base64.getDecoder()
            .decode(parts[1]);
        byte[] ct = Base64.getDecoder()
            .decode(parts[2]);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new IvParameterSpec(iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /**
     * PBKDF2: expands the short user passphrase into a 128-bit AES key. The factory returns a
     * {@code PBEKey} whose algorithm is "PBE" — it must be wrapped in a {@link SecretKeySpec}
     * with algorithm "AES" or {@code AES/CBC} init throws {@code InvalidKeyException:
     * Wrong algorithm}.
     */
    private static SecretKey deriveKey(String passphrase, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        SecretKey tmp = f.generateSecret(spec);
        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }
}
