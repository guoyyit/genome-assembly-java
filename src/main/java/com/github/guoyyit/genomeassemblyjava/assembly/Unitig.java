package com.github.guoyyit.genomeassemblyjava.assembly;

/**
 * A maximal non-branching path of the de Bruijn graph.
 *
 * @param sequence    base sequence of the path
 * @param kmerCount   number of k-mers on the path
 * @param coverageSum sum of k-mer counts along the path
 */
record Unitig(String sequence, int kmerCount, long coverageSum) {

    double meanCoverage() {
        return kmerCount == 0 ? 0.0 : coverageSum / (double) kmerCount;
    }
}
