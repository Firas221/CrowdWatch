package crowdwatch.ds;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * FIFO queue built on linked nodes with front and rear pointers.
 * <p>
 * CrowdMonitor keeps one queue per priority level for incidents that are waiting for a unit.
 * enqueue and dequeue are O(1). {@link #remove} (O(n)) takes out an incident that was
 * cancelled or escalated to another queue.
 *
 * @param <T> item type
 */
public class Queue<T> implements Iterable<T> {
    /** A queue node: one value and a link to the node behind it. */
    private static class Node<T> {
        T value;
        Node<T> next;
        Node(T value) { this.value = value; }
    }

    // next item to leave
    private Node<T> front;
    // most recently added item
    private Node<T> rear;
    private int size;

    /** Adds an item at the rear. O(1). */
    public void enqueue(T value) {
        Node<T> n = new Node<>(value);
        if (rear == null) { front = rear = n; } else { rear.next = n; rear = n; }
        size++;
    }

    /**
     * Removes and returns the item at the front. O(1).
     *
     * @throws NoSuchElementException if the queue is empty
     */
    public T dequeue() {
        if (front == null) throw new NoSuchElementException("queue is empty");
        T v = front.value;
        front = front.next;
        if (front == null) rear = null;
        size--;
        return v;
    }

    /**
     * @return the front item without removing it
     * @throws NoSuchElementException if the queue is empty
     */
    public T peek() {
        if (front == null) throw new NoSuchElementException("queue is empty");
        return front.value;
    }

    /**
     * Removes a specific item from anywhere in the queue. O(n).
     *
     * @return true if the item was found and removed
     */
    public boolean remove(T value) {
        Node<T> prev = null, cur = front;
        while (cur != null) {
            if (cur.value == value || (cur.value != null && cur.value.equals(value))) {
                if (prev == null) front = cur.next; else prev.next = cur.next;
                if (cur == rear) rear = prev;
                size--;
                return true;
            }
            prev = cur;
            cur = cur.next;
        }
        return false;
    }

    /** @return true if the queue has no items */
    public boolean isEmpty() { return size == 0; }

    /** @return number of items waiting */
    public int size() { return size; }

    /** Removes all items. */
    public void clear() { front = rear = null; size = 0; }

    /** @return an iterator from front to rear (does not remove items) */
    @Override
    public Iterator<T> iterator() {
        return new Iterator<T>() {
            private Node<T> cur = front;
            public boolean hasNext() { return cur != null; }
            public T next() {
                if (cur == null) throw new NoSuchElementException();
                T v = cur.value;
                cur = cur.next;
                return v;
            }
        };
    }

    /** @return the items from front to rear, e.g. front [I1 | I2] rear */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("front [");
        for (Node<T> n = front; n != null; n = n.next) {
            sb.append(n.value);
            if (n.next != null) sb.append(" | ");
        }
        return sb.append("] rear").toString();
    }
}
