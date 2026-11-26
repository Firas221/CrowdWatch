package crowdwatch.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts personal fields (incident details and contact) before they are written to the
 * database, with AES-256-GCM: a copy of the database or of a backup shows no names, phone numbers
 * or descriptions without the key. Encrypted values start with {@value #PREFIX}; values without it
 * (written by earlier versions) are read as they are and encrypted at the next save.
 * <p>
 * The key comes from the environment variable {@value #KEY_VARIABLE} (Base64 of 32 bytes) or from
 * the file data.key next to the venue databases, which is made the first time (readable by its
 * owner only). Keep a copy of the key somewhere else: without it the personal fields of the
 * database and its backups cannot be read.
 */
public final class FieldCipher {
    /** Environment variable with the key (Base64 of 32 bytes). */
    public static final String KEY_VARIABLE = "CROWDWATCH_DATA_KEY";
    /** Start of an encrypted value. */
    public static final String PREFIX = "enc1:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    FieldCipher(byte[] key) { this.key = new SecretKeySpec(key, "AES"); }

    /**
     * @param keyFile the key file to read, or to make if it does not exist
     * @return the cipher with the key from the environment variable or the file
     */
    static FieldCipher open(Path keyFile) throws IOException {
        String env = System.getenv(KEY_VARIABLE);
        if (env != null && !env.isBlank()) return new FieldCipher(checked(Base64.getDecoder().decode(env.trim())));
        if (!Files.exists(keyFile)) {
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            Path tmp = keyFile.resolveSibling(keyFile.getFileName() + ".tmp");
            Files.writeString(tmp, Base64.getEncoder().encodeToString(k) + System.lineSeparator(), StandardCharsets.US_ASCII);
            crowdwatch.net.Accounts.ownerOnlyFile(tmp);
            Files.move(tmp, keyFile);
        }
        return new FieldCipher(checked(Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.US_ASCII).trim())));
    }

    private static byte[] checked(byte[] k) {
        if (k.length != 32) throw new IllegalArgumentException("the data key must be 32 bytes (Base64 of 32 bytes)");
        return k;
    }

    /** @return the value encrypted (null and empty stay as they are) */
    String encrypt(String plain) {
        if (plain == null || plain.isEmpty() || plain.startsWith(PREFIX)) return plain;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(all);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /**
     * @return the value decrypted; a value without the prefix is returned as it is
     * @throws IllegalStateException if the value was encrypted with another key
     */
    String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) return stored;
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("personal data in the database was encrypted with a different key: restore the right data.key or "
                + KEY_VARIABLE, e);
        }
    }
}
