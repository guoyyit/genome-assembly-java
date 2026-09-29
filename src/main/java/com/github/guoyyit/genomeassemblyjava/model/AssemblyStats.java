package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 组装统计指标。
 */
public record AssemblyStats(long contigCount, long totalLength, long longestLength, long n50) {

    public static final AssemblyStats EMPTY = new AssemblyStats(0, 0, 0, 0);
}
