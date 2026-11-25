package crowdwatch.net;

import crowdwatch.ds.AVLTree;

/**
 * Short-lived cache for the read-only dashboard data (/api/areas, /api/summary, /api/incidents,
 * /api/alerts, /api/status).
 * <p>
 * Many dashboards ask for the same data every few seconds. A reply is reused for
 * {@link #TTL_MILLIS} milliseconds, so it is built once (under the monitor's lock) and then
 * served many times without touching the lock. Any change made through the API clears the
 * cache, so a terminal sees its own change at once.
 */
final class ReplyCache {
    /** How long a reply is reused, in milliseconds. */
    static final long TTL_MILLIS = 500;
    // request path and query -> {time built (ns), content type, body}
    private final AVLTree<String, Object[]> entries = new AVLTree<>();

    /** @return the cached reply {type, body} if it is fresh, otherwise null */
    synchronized String[] get(String key) {
        Object[] e = entries.search(key);
        if (e == null || System.nanoTime() - (Long) e[0] > TTL_MILLIS * 1_000_000) return null;
        return new String[] { (String) e[1], (String) e[2] };
    }

    /** Stores a reply. */
    synchronized void put(String key, String type, String body) {
        if (entries.size() > 1000) entries.clear();
        entries.insert(key, new Object[] { System.nanoTime(), type, body });
    }

    /** Forgets every reply (called after any change). */
    synchronized void clear() { entries.clear(); }
}
