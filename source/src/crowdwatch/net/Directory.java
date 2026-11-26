package crowdwatch.net;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;

/**
 * Checks a password against the company directory (LDAP, or Active Directory over LDAP) by
 * signing in to it as the user ("bind"). Nothing is read from the directory and nothing is stored.
 * <p>
 * Settings (system properties or environment variables):
 * <ul>
 * <li>{@code crowdwatch.ldapUrl} / {@code CROWDWATCH_LDAP_URL}: e.g. {@code ldaps://dc1.venue.local:636}
 * (use ldaps:// so the password is encrypted on the network);</li>
 * <li>{@code crowdwatch.ldapUser} / {@code CROWDWATCH_LDAP_USER}: how a user name becomes the
 * directory's login, {0} standing for the name: {@code {0}@venue.local} for Active Directory,
 * {@code uid={0},ou=people,dc=venue,dc=local} for OpenLDAP (default {@code {0}}).</li>
 * </ul>
 */
public final class Directory {
    private Directory() { }

    /** @return the directory's address, or null if none is set */
    public static String url() { return setting("crowdwatch.ldapUrl", "CROWDWATCH_LDAP_URL"); }

    private static String setting(String prop, String env) {
        String v = System.getProperty(prop);
        if (v == null || v.isBlank()) v = System.getenv(env);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * @return true if the directory accepts the name and password; false if it refuses them,
     *         no directory is set, or it cannot be reached
     */
    public static boolean bind(String name, String password) {
        String url = url();
        if (url == null || name == null || password == null || password.isEmpty()) return false;   // an empty password would be an anonymous bind
        if (!name.matches("[A-Za-z0-9._-]{1,64}")) return false;   // no characters that change the meaning of the login
        String pattern = setting("crowdwatch.ldapUser", "CROWDWATCH_LDAP_USER");
        String principal = (pattern == null ? "{0}" : pattern).replace("{0}", name);
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, principal);
        env.put(Context.SECURITY_CREDENTIALS, password);
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "5000");
        try {
            new InitialDirContext(env).close();
            return true;
        } catch (NamingException e) {
            return false;
        }
    }
}
