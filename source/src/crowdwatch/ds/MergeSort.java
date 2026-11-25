package crowdwatch.ds;

/**
 * Top-down merge sort for arrays. O(n log n) in every case and stable: items that compare
 * equal keep their original order (so incidents with the same priority stay in time order).
 */
public final class MergeSort {
    // utility class, no instances
    private MergeSort() {}

    /**
     * Sorts an array in place.
     *
     * @param a   the array
     * @param cmp the ordering
     */
    public static <T> void sort(T[] a, Compare<T> cmp) { sort(a, cmp, null); }

    /**
     * Sorts an array in place and optionally records every merge step.
     *
     * @param trace if not null, one line per merge is added (shown in the "Data structures" tab)
     */
    public static <T> void sort(T[] a, Compare<T> cmp, LinkedList<String> trace) {
        if (a.length < 2) return;
        @SuppressWarnings("unchecked")
        T[] tmp = (T[]) new Object[a.length];
        sort(a, tmp, 0, a.length - 1, cmp, trace, 0);
    }

    /**
     * Sorts a[lo..hi]: sort each half recursively, then merge the halves through {@code tmp}.
     * Using {@code <=} when comparing takes the left item first on ties, which makes the sort stable.
     */
    private static <T> void sort(T[] a, T[] tmp, int lo, int hi, Compare<T> cmp, LinkedList<String> trace, int depth) {
        if (lo >= hi) return;
        int mid = (lo + hi) / 2;
        sort(a, tmp, lo, mid, cmp, trace, depth + 1);
        sort(a, tmp, mid + 1, hi, cmp, trace, depth + 1);
        int i = lo, j = mid + 1, k = lo;
        // merge: take the smaller head of the two halves; on a tie take the left one (stability)
        while (i <= mid && j <= hi) tmp[k++] = cmp.compare(a[i], a[j]) <= 0 ? a[i++] : a[j++];
        // copy whatever is left of either half
        while (i <= mid) tmp[k++] = a[i++];
        while (j <= hi) tmp[k++] = a[j++];
        // copy the merged run back into the array
        for (k = lo; k <= hi; k++) a[k] = tmp[k];
        if (trace != null) {
            StringBuilder sb = new StringBuilder("  ".repeat(depth)).append("merge [").append(lo).append("..").append(hi).append("]: ");
            for (k = lo; k <= hi; k++) sb.append(a[k]).append(k < hi ? ", " : "");
            trace.addLast(sb.toString());
        }
    }
}
