package crowdwatch.ds;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Singly linked list with head and tail pointers.
 * <p>
 * Used for alerts, incident and unit histories, unit skills, listeners and search paths.
 * Adding at either end is O(1); get(index), remove and contains are O(n).
 * Implements Iterable so it can be used in for-each loops.
 *
 * @param <T> item type
 */
public class LinkedList<T> implements Iterable<T> {
    /** A list node: one value and a link to the next node. */
    private static class Node<T> {
        T value;
        Node<T> next;
        Node(T value) { this.value = value; }
    }

    // first node, null when empty
    private Node<T> head;
    // last node, kept so addLast is O(1)
    private Node<T> tail;
    private int size;

    /** Appends an item at the end. O(1). */
    public void addLast(T value) {
        Node<T> n = new Node<>(value);
        if (head == null) { head = tail = n; } else { tail.next = n; tail = n; }
        size++;
    }

    /** Inserts an item at the front. O(1). */
    public void addFirst(T value) {
        Node<T> n = new Node<>(value);
        n.next = head;
        head = n;
        if (tail == null) tail = n;
        size++;
    }

    /**
     * Returns the item at a position by walking from the head. O(n).
     *
     * @throws IndexOutOfBoundsException if index is outside 0..size()-1
     */
    public T get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index " + index);
        Node<T> cur = head;
        for (int i = 0; i < index; i++) cur = cur.next;
        return cur.value;
    }

    /**
     * @return the first item
     * @throws NoSuchElementException if the list is empty
     */
    public T getFirst() { if (head == null) throw new NoSuchElementException(); return head.value; }

    /**
     * @return the last item
     * @throws NoSuchElementException if the list is empty
     */
    public T getLast() { if (tail == null) throw new NoSuchElementException(); return tail.value; }

    /**
     * Removes and returns the last item. O(n), because a singly linked list has to walk
     * to the node before the tail. Used to drop the oldest alert when the list is full.
     *
     * @throws NoSuchElementException if the list is empty
     */
    public T removeLast() {
        if (tail == null) throw new NoSuchElementException();
        T v = tail.value;
        if (head == tail) { head = tail = null; }
        else {
            Node<T> cur = head;
            while (cur.next != tail) cur = cur.next;
            cur.next = null;
            tail = cur;
        }
        size--;
        return v;
    }

    /**
     * Removes the first item equal to {@code value}. O(n).
     * Updates head and tail when the removed node is at either end.
     *
     * @return true if an item was removed
     */
    public boolean remove(T value) {
        Node<T> prev = null, cur = head;
        while (cur != null) {
            if (cur.value == value || (cur.value != null && cur.value.equals(value))) {
                if (prev == null) head = cur.next; else prev.next = cur.next;
                if (cur == tail) tail = prev;
                size--;
                return true;
            }
            prev = cur;
            cur = cur.next;
        }
        return false;
    }

    /** @return true if the list holds an item equal to {@code value}. O(n). */
    public boolean contains(T value) {
        for (T v : this) if (v == value || (v != null && v.equals(value))) return true;
        return false;
    }

    /** Removes all items. */
    public void clear() { head = tail = null; size = 0; }

    /** @return number of items */
    public int size() { return size; }

    /** @return true if the list has no items */
    public boolean isEmpty() { return size == 0; }

    /**
     * Copies the items into a new array (used before merge-sorting a list).
     *
     * @param template any array of the wanted type; only its element type is used
     * @return a new array of exactly size() items, in list order
     */
    @SuppressWarnings("unchecked")
    public T[] toArray(T[] template) {
        T[] out = (T[]) java.lang.reflect.Array.newInstance(template.getClass().getComponentType(), size);
        int i = 0;
        for (Node<T> n = head; n != null; n = n.next) out[i++] = n.value;
        return out;
    }

    /** @return an iterator from head to tail */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private Node<T> cur = head;
            public boolean hasNext() { return cur != null; }
            public T next() {
                if (cur == null) throw new NoSuchElementException();
                T v = cur.value;
                cur = cur.next;
                return v;
            }
        };
    }

    /** @return the items joined with arrows, e.g. [A -> B -> C] */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (Node<T> n = head; n != null; n = n.next) {
            sb.append(n.value);
            if (n.next != null) sb.append(" -> ");
        }
        return sb.append("]").toString();
    }
}
