package crowdwatch.ds;

/**
 * Comparison function used by {@link MergeSort} and {@link MinHeap}
 * (our own replacement for java.util.Comparator).
 *
 * @param <T> type being compared
 */
public interface Compare<T> {
    /** @return a negative number if a comes before b, 0 if they are equal, positive if a comes after b */
    int compare(T a, T b);
}
