import java.util.Objects;
import java.util.Arrays;

/**
 * Minimal ArrayList-like growable array backed by Object[].
 * - doubles capacity when full
 * - supports add(E), get(int), contains(Object), size(), clear(), toString()
 */
public class MyArrayList<E> {
    private Object[] data;
    private int size;
    private static final int DEFAULT_CAPACITY = 8;

    public MyArrayList() {
        this.data = new Object[DEFAULT_CAPACITY];
        this.size = 0;
    }

    public MyArrayList(int initialCapacity) {
        if (initialCapacity <= 0) initialCapacity = DEFAULT_CAPACITY;
        this.data = new Object[initialCapacity];
        this.size = 0;
    }

    public int size() { return size; }

    @SuppressWarnings("unchecked")
    public E get(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index: " + index + " Size: " + size);
        return (E) data[index];
    }

    public boolean contains(Object o) {
        if (o == null) {
            for (int i = 0; i < size; i++) if (data[i] == null) return true;
            return false;
        }
        for (int i = 0; i < size; i++) if (o.equals(data[i])) return true;
        return false;
    }

    public void add(E e) {
        ensureCapacity(size + 1);
        data[size++] = e;
    }

    private void ensureCapacity(int minCapacity) {
        if (minCapacity <= data.length) return;
        int newCap = data.length * 2;
        if (newCap < minCapacity) newCap = minCapacity;
        data = Arrays.copyOf(data, newCap);
    }

    public void clear() {
        Arrays.fill(data, 0, size, null);
        size = 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < size; i++) {
            if (i > 0) sb.append(',');
            sb.append(data[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
