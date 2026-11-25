package crowdwatch.ds;

/**
 * Self-balancing binary search tree (AVL tree) that maps keys to values.
 * <p>
 * After every insert and delete the tree checks the balance factor of each node on the
 * path back to the root and rotates where needed, so the height never exceeds about
 * 1.44 log2(n). Search, insert and delete are therefore O(log n) even when keys arrive
 * in sorted order (for example sequential incident IDs), where a plain BST would become
 * a linked list with O(n) search.
 * <p>
 * The tree also records the path taken by the last operation ({@link #lastPath()}) so
 * the GUI and the tests can show how a search moved through the tree.
 *
 * @param <K> key type, must be comparable
 * @param <V> value type
 */
public class AVLTree<K extends Comparable<K>, V> {
    /** A tree node: key, value, two children and the height of the subtree rooted here. */
    private class Node {
        K key;
        V value;
        Node left, right;
        int height = 1;
        Node(K key, V value) { this.key = key; this.value = value; }
    }

    // root of the tree, null when empty
    private Node root;
    private int size;
    // number of rotations performed since creation (shown in the GUI)
    private int rotations;
    // steps of the most recent insert, search or delete
    private final LinkedList<String> lastPath = new LinkedList<>();

    /**
     * Inserts a key, or replaces the value if the key already exists. O(log n).
     *
     * @param key   the key
     * @param value the value stored under the key
     * @return true if a new key was added, false if an existing value was replaced
     */
    public boolean insert(K key, V value) {
        lastPath.clear();
        int before = size;
        root = insert(root, key, value);
        return size > before;
    }

    /**
     * Recursive insert into the subtree {@code n}; rebalances on the way back up.
     *
     * @return the new root of this subtree (it can change after a rotation)
     */
    private Node insert(Node n, K key, V value) {
        if (n == null) {
            size++;
            lastPath.addLast(key + " (inserted)");
            return new Node(key, value);
        }
        int c = key.compareTo(n.key);
        if (c == 0) {
            lastPath.addLast(n.key + " (replaced)");
            n.value = value;
            return n;
        }
        lastPath.addLast(n.key + (c < 0 ? " go left" : " go right"));
        if (c < 0) n.left = insert(n.left, key, value); else n.right = insert(n.right, key, value);
        return balance(n);
    }

    /**
     * Finds the value stored under a key. Iterative, O(log n).
     *
     * @param key the key to look for
     * @return the value, or null if the key is not in the tree
     */
    public V search(K key) {
        lastPath.clear();
        Node cur = root;
        while (cur != null) {
            int c = key.compareTo(cur.key);
            if (c == 0) {
                lastPath.addLast(cur.key + " (found)");
                return cur.value;
            }
            lastPath.addLast(cur.key + (c < 0 ? " go left" : " go right"));
            cur = c < 0 ? cur.left : cur.right;
        }
        lastPath.addLast("null (not found)");
        return null;
    }

    /** @return true if the key is in the tree */
    public boolean contains(K key) { return search(key) != null; }

    /**
     * Removes a key and its value. O(log n).
     *
     * @param key the key to remove
     * @return true if the key was found and removed
     */
    public boolean delete(K key) {
        lastPath.clear();
        int before = size;
        root = delete(root, key);
        if (size == before) lastPath.addLast("null (not found)");
        return size < before;
    }

    /**
     * Recursive delete from the subtree {@code n}.
     * A node with two children is replaced by its in-order successor (the smallest key in its
     * right subtree), and the successor is then deleted from the right subtree.
     *
     * @return the new root of this subtree
     */
    private Node delete(Node n, K key) {
        if (n == null) return null;
        int c = key.compareTo(n.key);
        if (c < 0) { lastPath.addLast(n.key + " go left"); n.left = delete(n.left, key); }
        else if (c > 0) { lastPath.addLast(n.key + " go right"); n.right = delete(n.right, key); }
        else if (n.left == null || n.right == null) {
            lastPath.addLast(n.key + " (removed)");
            size--;
            return n.left != null ? n.left : n.right;
        } else {
            Node s = n.right;
            while (s.left != null) s = s.left;
            lastPath.addLast(n.key + " (two children: replaced by successor " + s.key + ")");
            n.key = s.key;
            n.value = s.value;
            n.right = delete(n.right, s.key);
        }
        return balance(n);
    }

    /** @return height of a subtree, 0 for an empty one */
    private static int h(AVLTree<?, ?>.Node n) { return n == null ? 0 : n.height; }

    /** Recomputes a node's height from its children. */
    private void update(Node n) { n.height = 1 + Math.max(h(n.left), h(n.right)); }

    /** Balance factor: height(left) - height(right). AVL keeps it in -1..1. */
    private int factor(Node n) { return h(n.left) - h(n.right); }

    /**
     * Right rotation, used when the left side is too tall.
     * <pre>
     *       y            x
     *      / \          / \
     *     x   C   -&gt;   A   y
     *    / \              / \
     *   A   B            B   C
     * </pre>
     * @return the new subtree root (x)
     */
    private Node rotateRight(Node y) {
        Node x = y.left;
        y.left = x.right;
        x.right = y;
        update(y);
        update(x);
        rotations++;
        lastPath.addLast("rotate right at " + y.key);
        return x;
    }

    /**
     * Left rotation, the mirror image of {@link #rotateRight}.
     *
     * @return the new subtree root (y)
     */
    private Node rotateLeft(Node x) {
        Node y = x.right;
        x.right = y.left;
        y.left = x;
        update(x);
        update(y);
        rotations++;
        lastPath.addLast("rotate left at " + x.key);
        return y;
    }

    /**
     * Restores the AVL property at node {@code n} after an insert or delete below it.
     * <ul>
     * <li>Left-heavy (factor &gt; 1): single right rotation (LL case), or a left rotation of the
     * left child first (LR case).</li>
     * <li>Right-heavy (factor &lt; -1): single left rotation (RR case), or a right rotation of
     * the right child first (RL case).</li>
     * </ul>
     * @return the balanced subtree root
     */
    private Node balance(Node n) {
        update(n);
        int f = factor(n);
        if (f > 1) {
            if (factor(n.left) < 0) n.left = rotateLeft(n.left);
            return rotateRight(n);
        }
        if (f < -1) {
            if (factor(n.right) > 0) n.right = rotateRight(n.right);
            return rotateLeft(n);
        }
        return n;
    }

    /** Returns all values in ascending key order (in-order traversal). O(n). */
    public LinkedList<V> inOrder() {
        LinkedList<V> out = new LinkedList<>();
        inOrder(root, out);
        return out;
    }

    /** Recursive in-order traversal: left subtree, node, right subtree. */
    private void inOrder(Node n, LinkedList<V> out) {
        if (n == null) return;
        inOrder(n.left, out);
        out.addLast(n.value);
        inOrder(n.right, out);
    }

    /** Returns all keys in ascending order. O(n). */
    public LinkedList<K> keysInOrder() {
        LinkedList<K> out = new LinkedList<>();
        keys(root, out);
        return out;
    }

    /** Recursive in-order traversal that collects keys. */
    private void keys(Node n, LinkedList<K> out) {
        if (n == null) return;
        keys(n.left, out);
        out.addLast(n.key);
        keys(n.right, out);
    }

    /** @return height of the tree (0 when empty, 1 for a single node) */
    public int height() { return h(root); }

    /** @return number of keys stored */
    public int size() { return size; }

    /** @return total number of rotations performed */
    public int rotations() { return rotations; }

    /** @return true if the tree holds no keys */
    public boolean isEmpty() { return size == 0; }

    /** Removes every key. */
    public void clear() { root = null; size = 0; }

    /** @return the steps taken by the last insert, search or delete (for display and tests) */
    public LinkedList<String> lastPath() { return lastPath; }

    /** Draws the tree sideways as text, one key per line, for the "Data structures" tab. */
    public String shape() {
        StringBuilder sb = new StringBuilder();
        shape(root, "", true, sb);
        return sb.length() == 0 ? "(empty)" : sb.toString();
    }

    /** Recursive helper for {@link #shape()}; {@code prefix} holds the indentation lines. */
    private void shape(Node n, String prefix, boolean last, StringBuilder sb) {
        if (n == null) return;
        sb.append(prefix).append(prefix.isEmpty() ? "" : (last ? "\\-- " : "|-- ")).append(n.key).append('\n');
        String next = prefix.isEmpty() ? "    " : prefix + (last ? "    " : "|   ");
        boolean hasRight = n.right != null;
        if (n.left != null) shape(n.left, next, !hasRight, sb);
        if (n.right != null) shape(n.right, next, true, sb);
    }
}
