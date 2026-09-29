package com.github.guoyyit.genomeassemblyjava.assembly;

/**
 * A single short read with base qualities (FASTQ style).
 *
 * @param name     read identifier, informational only
 * @param sequence base sequence over {A,C,G,T,N}, case-insensitive
 * @param quality  ASCII-encoded Phred qualities, same length as sequence
 */
public record Read(String name, String sequence, String quality) {
    public Read {
        if (sequence == null || sequence.isEmpty()) {
            throw new IllegalArgumentException("read sequence must not be empty");
        }
        String upper = sequence.toUpperCase(java.util.Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            if (c != 'A' && c != 'C' && c != 'G' && c != 'T' && c != 'N') {
                throw new IllegalArgumentException("illegal base '" + c + "' in read " + name);
            }
        }
        sequence = upper;
        if (quality == null) {
            throw new IllegalArgumentException("read quality must not be null");
        }
        if (quality.length() != sequence.length()) {
            throw new IllegalArgumentException(
                    "quality length " + quality.length() + " != sequence length " + sequence.length()
                            + " in read " + name);
        }
    }
}
