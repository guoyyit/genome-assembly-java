package com.github.guoyyit.genomeassemblyjava.assembly;

/**
 * One assembled contiguous sequence.
 *
 * @param name         deterministic contig name
 * @param sequence     base sequence over {A,C,G,T}
 * @param meanCoverage mean k-mer coverage along the contig
 */
public record Contig(String name, String sequence, double meanCoverage) {
}
