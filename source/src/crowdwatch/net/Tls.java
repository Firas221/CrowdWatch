package crowdwatch.net;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * HTTPS for the built-in server.
 * <p>
 * The server's certificate and private key are read from a PKCS12 key store (.p12). For a real
 * venue, use a certificate from the venue's own certificate authority or a public one (convert it
 * to .p12 with openssl or keytool). For a first test, {@link #selfSigned} makes a self-signed
 * certificate with the JDK's keytool: the connection is encrypted, but browsers warn until the
 * certificate is trusted on each device.
 * <p>
 * The key store password is read from the environment variable {@value #PASSWORD_VARIABLE}
 * so it never appears in the process list or in scripts.
 */
public final class Tls {
    /** Environment variable that holds the key store password. */
    public static final String PASSWORD_VARIABLE = "CROWDWATCH_KEYSTORE_PASSWORD";

    private Tls() { }

    /**
     * Loads a PKCS12 key store and builds a TLS context with its key.
     *
     * @throws IOException if the file cannot be read or the password is wrong
     */
    public static SSLContext context(File keyStore, char[] password) throws IOException {
        try (InputStream in = new FileInputStream(keyStore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(kmf.getKeyManagers(), null, null);
            return c;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("the key store " + keyStore + " cannot be used: " + e.getMessage(), e);
        }
    }

    /**
     * A TLS context for a client that trusts exactly the certificates in a key store (used by the
     * tests, and by tools that talk to a server with a self-signed certificate).
     */
    public static SSLContext trusting(File keyStore, char[] password) throws IOException {
        try (InputStream in = new FileInputStream(keyStore)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password);
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(null, tmf.getTrustManagers(), null);
            return c;
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Makes a self-signed certificate (RSA 3072, valid 825 days) for the host names with the
     * JDK's keytool, unless the key store already exists.
     *
     * @param hosts host names and IP addresses the certificate is for, e.g. "localhost", "10.0.0.5"
     * @throws IOException if keytool is missing or fails
     */
    public static void selfSigned(File keyStore, char[] password, String... hosts) throws IOException {
        if (keyStore.exists()) return;
        if (keyStore.getAbsoluteFile().getParentFile() != null) keyStore.getAbsoluteFile().getParentFile().mkdirs();
        File keytool = new File(System.getProperty("java.home"), "bin" + File.separator + (File.separatorChar == '\\' ? "keytool.exe" : "keytool"));
        if (!keytool.exists()) throw new IOException("keytool not found in " + keytool.getParent());
        StringBuilder san = new StringBuilder();
        for (String h : hosts) {
            if (h == null || h.isBlank()) continue;
            if (san.length() > 0) san.append(',');
            san.append(h.matches("[0-9.]+|[0-9a-fA-F:]+") ? "ip:" : "dns:").append(h.trim());
        }
        if (san.length() == 0) san.append("dns:localhost,ip:127.0.0.1");
        String pw = new String(password);
        ProcessBuilder pb = new ProcessBuilder(keytool.getPath(), "-genkeypair", "-alias", "crowdwatch", "-keyalg", "RSA", "-keysize", "3072",
            "-validity", "825", "-dname", "CN=CrowdWatch server", "-ext", "san=" + san, "-storetype", "PKCS12",
            "-keystore", keyStore.getPath(), "-storepass:env", "CW_STOREPASS", "-keypass:env", "CW_STOREPASS");
        pb.environment().put("CW_STOREPASS", pw);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        try {
            if (p.waitFor() != 0) throw new IOException("keytool failed: " + out.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while making the certificate");
        }
    }

    /** @return the key store password from {@value #PASSWORD_VARIABLE}, or null if it is not set */
    public static char[] passwordFromEnvironment() {
        String v = System.getenv(PASSWORD_VARIABLE);
        return v == null || v.isEmpty() ? null : v.toCharArray();
    }
}
