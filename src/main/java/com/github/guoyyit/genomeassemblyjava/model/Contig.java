package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 一条连续序列。
 */
public record Contig(String id, String sequence, long length, double meanCoverage) {
}
