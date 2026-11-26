package crowdwatch.net;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time codes (TOTP, RFC 6238) as shown by authenticator apps: HMAC-SHA1, 6 digits,
 * a new code every 30 seconds. One step before and after is accepted, so a phone clock that is
 * up to 30 seconds off still works.
 */
public final class Totp {
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Totp() { }

    /** @return a new random 160-bit secret in Base32 (32 characters) */
    public static String newSecret(SecureRandom random) {
        byte[] b = new byte[20];
        random.nextBytes(b);
        return base32(b);
    }

    /** @return true if the code matches the secret at this time (or one 30-second step either side) */
    public static boolean verify(String secret, String code, LocalDateTime now) {
        if (secret == null || code == null) return false;
        String c = code.replaceAll("\\s", "");
        if (!c.matches("\\d{6}")) return false;
        long step = now.atZone(ZoneId.systemDefault()).toEpochSecond() / 30;
        boolean ok = false;
        for (long d = -1; d <= 1; d++) ok |= java.security.MessageDigest.isEqual(code(secret, step + d).getBytes(), c.getBytes());
        return ok;
    }

    /** @return the 6-digit code of a secret for a 30-second step (used by the tests) */
    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(unbase32(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0xf;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the code for a time (used by the tests and the set-up check) */
    public static String code(String secret, LocalDateTime t) { return code(secret, t.atZone(ZoneId.systemDefault()).toEpochSecond() / 30); }

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buf = 0, bits = 0;
        for (byte x : b) {
            buf = (buf << 8) | (x & 0xff);
            bits += 8;
            while (bits >= 5) { sb.append(BASE32.charAt((buf >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(BASE32.charAt((buf << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] unbase32(String s) {
        s = s.replaceAll("[\\s=-]", "").toUpperCase();
        ByteBuffer out = ByteBuffer.allocate(s.length() * 5 / 8);
        int buf = 0, bits = 0;
        for (char ch : s.toCharArray()) {
            int v = BASE32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("not Base32");
            buf = (buf << 5) | v;
            bits += 5;
            if (bits >= 8) { out.put((byte) (buf >> (bits - 8))); bits -= 8; }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }
}
