package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.List;

/**
 * Summary statistics of an assembly result.
 *
 * @param contigCount  number of reported contigs
 * @param totalLength  sum of contig lengths in bases
 * @param maxLength    length of the longest contig
 * @param n50          length of the shortest contig such that contigs of this length or longer
 *                     cover at least half of totalLength
 */
public record AssemblyStats(int contigCount, long totalLength, int maxLength, int n50) {

    /**
     * Computes statistics from contig sequences. Deterministic for a given contig list.
     */
    public static AssemblyStats of(List<Contig> contigs) {
        int[] lengths = contigs.stream().mapToInt(c -> c.sequence().length()).sorted().toArray();
        long total = 0;
        for (int len : lengths) {
            total += len;
        }
        int max = lengths.length == 0 ? 0 : lengths[lengths.length - 1];
        int n50 = 0;
        long half = (total + 1) / 2;
        long acc = 0;
        for (int i = lengths.length - 1; i >= 0; i--) {
            acc += lengths[i];
            if (acc >= half) {
                n50 = lengths[i];
            } else {
                break;
            }
        }
        return new AssemblyStats(lengths.length, total, max, n50);
    }
}
