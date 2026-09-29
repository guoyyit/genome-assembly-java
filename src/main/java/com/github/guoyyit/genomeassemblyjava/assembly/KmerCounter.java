package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts canonical k-mers of submitted reads, honoring base qualities.
 * Not thread-safe; callers synchronize.
 */
public final class KmerCounter {

    private final int k;
    private final int minBaseQuality;
    private final long mask;
    private final Map<Long, Integer> counts = new HashMap<>();

    public KmerCounter(int k, int minBaseQuality) {
        if (k < 3 || k > 32 || k % 2 == 0) {
            throw new IllegalArgumentException("k must be odd and in [3,32], got " + k);
        }
        this.k = k;
        this.minBaseQuality = minBaseQuality;
        this.mask = k == 32 ? -1L : (1L << (2 * k)) - 1L;
    }

    public int k() {
        return k;
    }

    public void add(Read read) {
        String seq = read.sequence();
        String qual = read.quality();
        long fwd = 0;
        long rev = 0;
        int run = 0;
        int shift = 2 * (k - 1);
        for (int i = 0; i < seq.length(); i++) {
            int bits = baseToBits(seq.charAt(i));
            boolean qualityOk = minBaseQuality <= 0 || qual.charAt(i) - 33 >= minBaseQuality;
            if (bits < 0 || !qualityOk) {
                run = 0;
                fwd = 0;
                rev = 0;
                continue;
            }
            fwd = ((fwd << 2) | bits) & mask;
            rev = (rev >>> 2) | ((long) (3 - bits) << shift);
            run++;
            if (run >= k) {
                long canonical = Math.min(fwd, rev);
                counts.merge(canonical, 1, Integer::sum);
            }
        }
    }

    public void addAll(List<Read> reads) {
        for (Read read : reads) {
            add(read);
        }
    }

    public long distinctKmers() {
        return counts.size();
    }

    public Map<Long, Integer> snapshotCounts() {
        return new HashMap<>(counts);
    }

    public void restoreCounts(Map<Long, Integer> counts) {
        this.counts.putAll(counts);
    }

    static int baseToBits(char c) {
        return switch (c) {
            case 'A' -> 0;
            case 'C' -> 1;
            case 'G' -> 2;
            case 'T' -> 3;
            default -> -1;
        };
    }

    static char bitsToBase(int bits) {
        return "ACGT".charAt(bits);
    }

    static long encode(String s) {
        long code = 0;
        for (int i = 0; i < s.length(); i++) {
            int bits = baseToBits(s.charAt(i));
            if (bits < 0) {
                throw new IllegalArgumentException("cannot encode base '" + s.charAt(i) + "'");
            }
            code = (code << 2) | bits;
        }
        return code;
    }

    static String decode(long code, int k) {
        char[] chars = new char[k];
        for (int i = k - 1; i >= 0; i--) {
            chars[i] = bitsToBase((int) (code & 3L));
            code >>>= 2;
        }
        return new String(chars);
    }

    static long reverseComplement(long code, int k) {
        long rc = 0;
        for (int i = 0; i < k; i++) {
            rc = (rc << 2) | (3 - (code & 3L));
            code >>>= 2;
        }
        return rc;
    }

    static long canonical(long code, int k) {
        return Math.min(code, reverseComplement(code, k));
    }
}
