package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.List;

/**
 * Immutable snapshot of an assembly: contigs plus summary statistics.
 */
public record AssemblyResult(List<Contig> contigs, AssemblyStats stats) {

    /**
     * Renders the contigs as a FASTA document. Deterministic for a given result.
     */
    public String toFasta() {
        StringBuilder sb = new StringBuilder();
        for (Contig contig : contigs) {
            sb.append('>').append(contig.name()).append('\n');
            sb.append(contig.sequence()).append('\n');
        }
        return sb.toString();
    }
}
