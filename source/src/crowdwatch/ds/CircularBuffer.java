package crowdwatch.ds;

/**
 * Fixed-size ring buffer that keeps only the newest items.
 * <p>
 * Used to keep the last 60 people-count readings of each area for the trend warning.
 * When the buffer is full, adding a new item overwrites the oldest one, so memory is fixed
 * and every operation is O(1).
 *
 * @param <T> item type
 */
public class CircularBuffer<T> {
    // storage array; its length is the capacity
    private final Object[] a;
    // index of the oldest item
    private int start;
    // number of items currently stored (at most a.length)
    private int size;

    /**
     * Creates an empty buffer.
     *
     * @param capacity maximum number of items kept
     */
    public CircularBuffer(int capacity) { a = new Object[capacity]; }

    /**
     * Adds an item at the end. If the buffer is full, the oldest item is overwritten and the
     * start moves forward by one. O(1).
     */
    public void add(T value) {
        if (size < a.length) a[(start + size++) % a.length] = value;
        else { a[start] = value; start = (start + 1) % a.length; }
    }

    /**
     * Returns the i-th item, where 0 is the oldest and size()-1 the newest. O(1).
     *
     * @throws IndexOutOfBoundsException if i is outside 0..size()-1
     */
    @SuppressWarnings("unchecked")
    public T get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("index " + i);
        return (T) a[(start + i) % a.length];
    }

    /**
     * Replaces the newest item. O(1). Used to keep only one stored reading per sampling
     * interval: a newer count overwrites the newest reading instead of pushing out an old one.
     *
     * @throws IllegalStateException if the buffer is empty
     */
    public void replaceLast(T value) {
        if (size == 0) throw new IllegalStateException("buffer is empty");
        a[(start + size - 1) % a.length] = value;
    }

    /** @return the newest item, or null if the buffer is empty */
    public T last() { return size == 0 ? null : get(size - 1); }

    /** @return number of items stored */
    public int size() { return size; }

    /** @return maximum number of items the buffer can hold */
    public int capacity() { return a.length; }

    /** Removes all items. */
    public void clear() { start = 0; size = 0; }

    /** @return the items from oldest to newest, e.g. [3, 4, 5] */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) sb.append(get(i)).append(i < size - 1 ? ", " : "");
        return sb.append("]").toString();
    }
}
