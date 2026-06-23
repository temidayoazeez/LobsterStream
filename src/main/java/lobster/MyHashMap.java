package lobster;

import java.util.Objects;

/**
 * Minimal HashMap-like implementation using separate chaining (linked lists).
 * - supports put(K,V), get(K), containsKey(K), size(), clear()
 * - resizes when load factor > 0.75
 */
public class MyHashMap<K,V> {
    static final int DEFAULT_CAPACITY = 16;
    static final double LOAD_FACTOR = 0.75;

    static final class Node<K,V> {
        final K key;
        V value;
        Node<K,V> next;
        Node(K k, V v, Node<K,V> n){ key = k; value = v; next = n; }
    }

    private Node<K,V>[] table;
    private int size = 0;

    @SuppressWarnings("unchecked")
    public MyHashMap() {
        table = (Node<K,V>[]) new Node[DEFAULT_CAPACITY];
    }

    public int size() { return size; }

    public V get(K key) {
        int idx = indexFor(key, table.length);
        for (Node<K,V> n = table[idx]; n != null; n = n.next) {
            if (Objects.equals(n.key, key)) return n.value;
        }
        return null;
    }

    public boolean containsKey(K key) {
        return get(key) != null;
    }

    public V put(K key, V value) {
        if ((double)(size + 1) / table.length > LOAD_FACTOR) resize(table.length * 2);
        int idx = indexFor(key, table.length);
        for (Node<K,V> n = table[idx]; n != null; n = n.next) {
            if (Objects.equals(n.key, key)) {
                V old = n.value;
                n.value = value;
                return old;
            }
        }
        table[idx] = new Node<>(key, value, table[idx]);
        size++;
        return null;
    }

    private void resize(int newCap) {
        @SuppressWarnings("unchecked") Node<K,V>[] newTable = (Node<K,V>[]) new Node[newCap];
        for (int i = 0; i < table.length; i++) {
            Node<K,V> n = table[i];
            while (n != null) {
                Node<K,V> next = n.next;
                int idx = indexFor(n.key, newCap);
                n.next = newTable[idx];
                newTable[idx] = n;
                n = next;
            }
        }
        table = newTable;
    }

    private int indexFor(K key, int len) {
        int h = (key == null) ? 0 : key.hashCode();
        return (h & 0x7FFFFFFF) % len;
    }

    public void clear() {
        for (int i = 0; i < table.length; i++) table[i] = null;
        size = 0;
    }
}
