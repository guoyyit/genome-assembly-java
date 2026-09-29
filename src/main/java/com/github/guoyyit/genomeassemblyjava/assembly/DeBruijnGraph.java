package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static operations over the canonical de Bruijn graph defined by a set of solid k-mers.
 */
final class DeBruijnGraph {

    private DeBruijnGraph() {
    }

    /**
     * Extracts all maximal non-branching paths. Deterministic: k-mers are visited in
     * ascending encoded order and neighbors in fixed base order.
     */
    static List<Unitig> extractUnitigs(Map<Long, Integer> kmers, int k) {
        List<Long> sorted = new ArrayList<>(kmers.keySet());
        Collections.sort(sorted);
        Set<Long> visited = new HashSet<>();
        List<Unitig> unitigs = new ArrayList<>();
        for (long code : sorted) {
            if (visited.contains(code)) {
                continue;
            }
            if (leftDegree(code, kmers, k) == 1 && rightDegree(code, kmers, k) == 1) {
                continue;
            }
            unitigs.add(buildUnitig(code, kmers, k, visited));
        }
        for (long code : sorted) {
            if (!visited.contains(code)) {
                unitigs.add(buildUnitig(code, kmers, k, visited));
            }
        }
        return unitigs;
    }

    private static Unitig buildUnitig(long start, Map<Long, Integer> kmers, int k, Set<Long> visited) {
        visited.add(start);
        StringBuilder right = new StringBuilder(KmerCounter.decode(start, k));
        long coverageSum = kmers.get(start);
        int kmerCount = 1;

        long cur = start;
        while (true) {
            int bits = uniqueRightBase(cur, kmers, k);
            if (bits < 0) {
                break;
            }
            long next = rightNeighbor(cur, k, bits);
            if (visited.contains(next) || leftDegree(next, kmers, k) != 1) {
                break;
            }
            visited.add(next);
            right.append(KmerCounter.bitsToBase(bits));
            coverageSum += kmers.get(next);
            kmerCount++;
            cur = next;
        }

        StringBuilder left = new StringBuilder();
        cur = start;
        while (true) {
            int bits = uniqueLeftBase(cur, kmers, k);
            if (bits < 0) {
                break;
            }
            long next = leftNeighbor(cur, k, bits);
            if (visited.contains(next) || rightDegree(next, kmers, k) != 1) {
                break;
            }
            visited.add(next);
            left.append(KmerCounter.bitsToBase(bits));
            coverageSum += kmers.get(next);
            kmerCount++;
            cur = next;
        }

        return new Unitig(left.reverse().toString() + right, kmerCount, coverageSum);
    }

    private static int uniqueRightBase(long code, Map<Long, Integer> kmers, int k) {
        int found = 0;
        int bits = -1;
        for (int b = 0; b < 4; b++) {
            if (kmers.containsKey(rightNeighbor(code, k, b))) {
                found++;
                bits = b;
            }
        }
        return found == 1 ? bits : -1;
    }

    private static int uniqueLeftBase(long code, Map<Long, Integer> kmers, int k) {
        int found = 0;
        int bits = -1;
        for (int b = 0; b < 4; b++) {
            if (kmers.containsKey(leftNeighbor(code, k, b))) {
                found++;
                bits = b;
            }
        }
        return found == 1 ? bits : -1;
    }

    static int leftDegree(long code, Map<Long, Integer> kmers, int k) {
        int degree = 0;
        for (int b = 0; b < 4; b++) {
            if (kmers.containsKey(leftNeighbor(code, k, b))) {
                degree++;
            }
        }
        return degree;
    }

    static int rightDegree(long code, Map<Long, Integer> kmers, int k) {
        int degree = 0;
        for (int b = 0; b < 4; b++) {
            if (kmers.containsKey(rightNeighbor(code, k, b))) {
                degree++;
            }
        }
        return degree;
    }

    static long rightNeighbor(long code, int k, int bits) {
        long mask = k == 32 ? -1L : (1L << (2 * k)) - 1L;
        return KmerCounter.canonical(((code << 2) | bits) & mask, k);
    }

    static long leftNeighbor(long code, int k, int bits) {
        return KmerCounter.canonical(((long) bits << (2 * (k - 1))) | (code >>> 2), k);
    }
}
