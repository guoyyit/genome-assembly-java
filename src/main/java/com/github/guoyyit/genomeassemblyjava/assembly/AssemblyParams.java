package com.github.guoyyit.genomeassemblyjava.assembly;

/**
 * Immutable configuration of one assembly task.
 *
 * @param k                   k-mer length, must be odd and in [3,32]
 * @param minKmerCount        k-mers seen fewer times are discarded as sequencing errors
 * @param minBaseQuality      k-mers containing a base with Phred quality below this are not counted; 0 disables
 * @param tipLength           dead-end paths with at most this many k-mers are clipped during simplification
 * @param tipCoverageRatio    a tip is clipped only when its mean coverage is at most this fraction of the
 *                            coverage of the unitigs attached at its junction
 * @param maxBubbleLength     only alternative paths with at most this many k-mers are considered simple bubbles
 * @param bubbleCoverageRatio the weaker side of a simple bubble is dropped when its mean coverage is below
 *                            this fraction of the stronger side
 * @param minContigLength     contigs shorter than this many bases are not reported
 */
public record AssemblyParams(
        int k,
        int minKmerCount,
        int minBaseQuality,
        int tipLength,
        double tipCoverageRatio,
        int maxBubbleLength,
        double bubbleCoverageRatio,
        int minContigLength) {

    public AssemblyParams {
        if (k < 3 || k > 32 || k % 2 == 0) {
            throw new IllegalArgumentException("k must be odd and in [3,32], got " + k);
        }
        if (minKmerCount < 1) {
            throw new IllegalArgumentException("minKmerCount must be >= 1, got " + minKmerCount);
        }
        if (minBaseQuality < 0 || minBaseQuality > 93) {
            throw new IllegalArgumentException("minBaseQuality must be in [0,93], got " + minBaseQuality);
        }
        if (tipLength < 0) {
            throw new IllegalArgumentException("tipLength must be >= 0, got " + tipLength);
        }
        if (tipCoverageRatio <= 0.0 || tipCoverageRatio > 1.0) {
            throw new IllegalArgumentException(
                    "tipCoverageRatio must be in (0,1], got " + tipCoverageRatio);
        }
        if (maxBubbleLength < 0) {
            throw new IllegalArgumentException("maxBubbleLength must be >= 0, got " + maxBubbleLength);
        }
        if (bubbleCoverageRatio <= 0.0 || bubbleCoverageRatio > 1.0) {
            throw new IllegalArgumentException(
                    "bubbleCoverageRatio must be in (0,1], got " + bubbleCoverageRatio);
        }
        if (minContigLength < 0) {
            throw new IllegalArgumentException("minContigLength must be >= 0, got " + minContigLength);
        }
    }

    public static AssemblyParams defaults() {
        return defaultsForK(31);
    }

    public static AssemblyParams defaultsForK(int k) {
        return new AssemblyParams(k, 2, 20, 2 * k, 0.5, k, 0.5, k);
    }
}
