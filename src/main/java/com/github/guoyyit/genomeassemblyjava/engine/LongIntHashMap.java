package com.github.guoyyit.genomeassemblyjava.engine;

/**
 * 开放寻址（线性探测）的 long -> int 计数器：避免装箱与 HashMap 的对象开销，
 * 保证高覆盖、大批次场景下内存占用有界。键 0 不允许使用（0 为槽位空标记）。
 */
final class LongIntHashMap {

    private static final long EMPTY_KEY = 0L;

    private long[] keys;
    private int[] values;
    private int size;
    private int threshold;

    LongIntHashMap() {
        this(1024);
    }

    LongIntHashMap(int expectedKeys) {
        int capacity = 16;
        while (capacity < (long) (expectedKeys * 2L)) {
            capacity <<= 1;
        }
        this.keys = new long[capacity];
        this.values = new int[capacity];
        this.threshold = capacity >> 1;
    }

    int size() {
        return size;
    }

    boolean containsKey(long key) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("key 0 is reserved");
        }
        return keys[indexOf(key)] != EMPTY_KEY;
    }

    int get(long key) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("key 0 is reserved");
        }
        int index = indexOf(key);
        return keys[index] == EMPTY_KEY ? 0 : values[index];
    }

    /**
     * 计数加 delta 后返回新值；计数饱和到 Integer.MAX_VALUE，避免超高覆盖时溢出。
     */
    int addAndGet(long key, int delta) {
        if (key == EMPTY_KEY) {
            throw new IllegalArgumentException("key 0 is reserved");
        }
        int index = probe(key);
        if (keys[index] == EMPTY_KEY) {
            keys[index] = key;
            values[index] = delta;
            size++;
            if (size > threshold) {
                rehash(keys.length << 1);
            }
            return delta;
        }
        long updated = (long) values[index] + delta;
        values[index] = (int) Math.min(updated, Integer.MAX_VALUE);
        return values[index];
    }

    /**
     * 以确定性顺序（按键升序）遍历全部条目。
     */
    void forEachSorted(EntryConsumer consumer) {
        long[] snapshotKeys = keys.clone();
        java.util.Arrays.sort(snapshotKeys);
        for (long key : snapshotKeys) {
            if (key == EMPTY_KEY) {
                continue;
            }
            consumer.accept(key, get(key));
        }
    }

    private int indexOf(long key) {
        int mask = keys.length - 1;
        int index = (int) (mix(key) & mask);
        while (true) {
            long existing = keys[index];
            if (existing == EMPTY_KEY || existing == key) {
                return index;
            }
            index = (index + 1) & mask;
        }
    }

    private int probe(long key) {
        return indexOf(key);
    }

    private void rehash(int newCapacity) {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[newCapacity];
        values = new int[newCapacity];
        threshold = newCapacity >> 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != EMPTY_KEY) {
                addAndGet(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static long mix(long value) {
        long h = value;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }

    @FunctionalInterface
    interface EntryConsumer {
        void accept(long key, int value);
    }
}
