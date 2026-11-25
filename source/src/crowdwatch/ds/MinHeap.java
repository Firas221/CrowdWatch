package crowdwatch.ds;

import java.util.NoSuchElementException;

/**
 * Binary min-heap stored in an array. The smallest item (by the given comparison) is always
 * at index 0. Used as the frontier in Dijkstra's algorithm.
 * <p>
 * For the node at index i, the children are at 2i+1 and 2i+2 and the parent at (i-1)/2.
 * insert and poll are O(log n), peek is O(1).
 *
 * @param <T> item type
 */
public class MinHeap<T> {
    // heap array; grows by doubling
    private Object[] a = new Object[16];
    private int size;
    private final Compare<T> cmp;

    /** @param cmp ordering; the item that compares smallest comes out first */
    public MinHeap(Compare<T> cmp) { this.cmp = cmp; }

    /**
     * Adds an item: place it at the end, then sift it up while it is smaller than its parent. O(log n).
     */
    public void insert(T value) {
        if (size == a.length) {
            Object[] b = new Object[a.length * 2];
            for (int i = 0; i < size; i++) b[i] = a[i];
            a = b;
        }
        a[size] = value;
        int i = size++;
        while (i > 0) {
            int p = (i - 1) / 2;
            if (cmp.compare(at(i), at(p)) >= 0) break;
            swap(i, p);
            i = p;
        }
    }

    /**
     * Removes and returns the smallest item: move the last item to the root, then sift it down,
     * swapping with the smaller child, until both children are larger. O(log n).
     *
     * @throws NoSuchElementException if the heap is empty
     */
    public T poll() {
        if (size == 0) throw new NoSuchElementException("heap is empty");
        T top = at(0);
        a[0] = a[--size];
        a[size] = null;
        int i = 0;
        while (true) {
            int l = 2 * i + 1, r = l + 1, m = i;
            if (l < size && cmp.compare(at(l), at(m)) < 0) m = l;
            if (r < size && cmp.compare(at(r), at(m)) < 0) m = r;
            if (m == i) break;
            swap(i, m);
            i = m;
        }
        return top;
    }

    /**
     * @return the smallest item without removing it
     * @throws NoSuchElementException if the heap is empty
     */
    public T peek() {
        if (size == 0) throw new NoSuchElementException("heap is empty");
        return at(0);
    }

    /** @return number of items */
    public int size() { return size; }

    /** @return true if the heap has no items */
    public boolean isEmpty() { return size == 0; }

    // typed read from the Object array
    @SuppressWarnings("unchecked")
    private T at(int i) { return (T) a[i]; }

    // swap two array slots
    private void swap(int i, int j) { Object t = a[i]; a[i] = a[j]; a[j] = t; }

    /** @return the heap array in index order (not sorted order) */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < size; i++) sb.append(a[i]).append(i < size - 1 ? ", " : "");
        return sb.append("]").toString();
    }
}
