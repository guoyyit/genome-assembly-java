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

    public AssemblyConfig {
        if (kmerSize < 3 || kmerSize > 31) {
            throw new IllegalArgumentException("kmerSize must be within 3..31: " + kmerSize);
        }
        if (minCoverage < 1 || minCoverage > 1_000_000) {
            throw new IllegalArgumentException("minCoverage must be within 1..1000000: " + minCoverage);
        }
        if (maxTipLength < 0 || maxTipLength > 1_000_000) {
            throw new IllegalArgumentException("maxTipLength must be within 0..1000000: " + maxTipLength);
        }
        if (qualityTrimThreshold < 0 || qualityTrimThreshold > 93) {
            throw new IllegalArgumentException(
                    "qualityTrimThreshold must be within 0..93: " + qualityTrimThreshold);
        }
        if (minReadLength < kmerSize || minReadLength > 1_000_000) {
            throw new IllegalArgumentException(
                    "minReadLength must be within kmerSize..1000000: " + minReadLength);
        }
    }

    public static AssemblyConfig defaults() {
        return new AssemblyConfig(21, 2, 42, 0, 21);
    }
}
