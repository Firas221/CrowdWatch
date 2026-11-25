package crowdwatch.core;

import crowdwatch.ds.Queue;

/**
 * Delivers listener calls on one background thread, in the order they were posted.
 * <p>
 * The monitor posts alerts and change notices here instead of calling the listeners while it
 * holds its lock. A slow listener (an SMS gateway, a PA system) therefore delays only the other
 * notifications, never the sensors, the dispatch rules or the other terminals. Listeners may
 * call back into the monitor safely, because they do not run under its lock.
 */
final class EventDispatcher {
    private final Queue<Runnable> queue = new Queue<>();
    // posted tasks not finished yet (queued or running)
    private int pending;
    // the delivery thread, started on first use
    private Thread thread;

    /** Adds a task at the end of the delivery queue. O(1); never blocks the caller. */
    synchronized void post(Runnable task) {
        queue.enqueue(task);
        pending++;
        if (thread == null) {
            thread = new Thread(this::run, "crowdwatch-events");
            thread.setDaemon(true);
            thread.start();
        }
        notifyAll();
    }

    /**
     * Delivery loop: waits for a task, runs it outside the lock, then counts it as done.
     * A listener that throws is reported and does not stop later deliveries.
     */
    private void run() {
        while (true) {
            Runnable task;
            synchronized (this) {
                while (queue.isEmpty()) {
                    try { wait(); } catch (InterruptedException e) { return; }
                }
                task = queue.dequeue();
            }
            try { task.run(); }
            catch (RuntimeException e) { System.err.println("CrowdWatch listener failed: " + e); }
            synchronized (this) { pending--; notifyAll(); }
        }
    }

    /**
     * Waits until every posted task has been delivered.
     *
     * @param timeoutMillis longest time to wait
     * @return true if all tasks finished, false if the time ran out
     */
    synchronized boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (pending > 0) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }
}
