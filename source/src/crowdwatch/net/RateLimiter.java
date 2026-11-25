package crowdwatch.net;

import crowdwatch.ds.AVLTree;

/**
 * Token-bucket rate limit per client address. Each address may send {@code perSecond}
 * requests a second on average, with short bursts of up to four times that. Used for
 * requests that do not carry the control key (scout devices and unknown clients), so one
 * device or attacker cannot flood the server. Addresses are kept in an AVL tree; if more than
 * 10,000 different addresses are seen, the tree is cleared to bound memory.
 */
final class RateLimiter {
    private final double perSecond;
    private final double burst;
    // address -> {tokens left, last refill time in ns}
    private final AVLTree<String, double[]> buckets = new AVLTree<>();

    /** @param perSecond average requests a second allowed per address; 0 or less switches the limit off */
    RateLimiter(int perSecond) {
        this.perSecond = perSecond;
        this.burst = Math.max(20, perSecond * 4.0);
    }

    /**
     * Takes one token for the address.
     *
     * @return true if the address is over its limit (the request should be refused with 429)
     */
    synchronized boolean tooMany(String address) {
        if (perSecond <= 0) return false;
        if (buckets.size() > 10_000) buckets.clear();
        long now = System.nanoTime();
        double[] b = buckets.search(address);
        if (b == null) { b = new double[] { burst, now }; buckets.insert(address, b); }
        b[0] = Math.min(burst, b[0] + (now - b[1]) / 1e9 * perSecond);
        b[1] = now;
        if (b[0] < 1) return true;
        b[0] -= 1;
        return false;
    }
}
