package com.github.guoyyit.genomeassemblyjava.model;

import java.util.List;

/**
 * 一次组装的不可变结果。
 */
public record AssemblyResult(AssemblyConfig config,
                             long readCount,
                             long batchCount,
                             List<Contig> contigs,
                             AssemblyStats stats) {
}
