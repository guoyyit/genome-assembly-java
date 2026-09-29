package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 组装参数。
 *
 * @param kmerSize              k-mer 长度（边长度），范围 3..31，默认 21
 * @param minCoverage           边（k-mer）最低保留覆盖度，低于该值视为测序错误，默认 2
 * @param maxTipLength          悬挂末端最大长度（边数），超过则保留，默认 2*k
 * @param qualityTrimThreshold  末端低质量修剪的 Phred 阈值，0 表示不修剪，默认 0
 * @param minReadLength         参与组装的最短读长（修剪后），短于该值的读段被丢弃，默认等于 k
 */
public record AssemblyConfig(int kmerSize,
                             int minCoverage,
                             int maxTipLength,
                             int qualityTrimThreshold,
                             int minReadLength) {

    public static AssemblyConfig defaults() {
        return new AssemblyConfig(21, 2, 42, 0, 21);
    }
}
