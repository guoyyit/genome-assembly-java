package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 组装参数。
 *
 * <p>JSON 反序列化时未提供的字段为 {@code null}，表示"使用默认值"；
 * 显式提供的非法值会在 {@link #fillDefaults} 中被拒绝。
 *
 * @param kmerSize              k-mer 长度（边长度），范围 3..31 的奇数，默认 21
 * @param minCoverage           边（k-mer）最低保留覆盖度，低于该值视为测序错误，默认 2
 * @param maxTipLength          悬挂末端最大长度（边数），超过则保留，默认 2*k
 * @param qualityTrimThreshold  末端低质量修剪的 Phred 阈值，0 表示不修剪，默认 0
 * @param minReadLength         参与组装的最短读长（修剪后），短于该值的读段被丢弃，默认等于 k
 */
public record AssemblyConfig(Integer kmerSize,
                             Integer minCoverage,
                             Integer maxTipLength,
                             Integer qualityTrimThreshold,
                             Integer minReadLength) {

    public static AssemblyConfig defaults() {
        return new AssemblyConfig(21, 2, 42, 0, 21);
    }

    /**
     * 用默认值填充 JSON 反序列化时缺失（字段为 null）的参数，并校验参数合法性。
     */
    public static AssemblyConfig fillDefaults(AssemblyConfig c) {
        AssemblyConfig d = defaults();
        if (c == null) {
            return d;
        }
        AssemblyConfig filled = new AssemblyConfig(
                c.kmerSize() != null ? c.kmerSize() : d.kmerSize(),
                c.minCoverage() != null ? c.minCoverage() : d.minCoverage(),
                c.maxTipLength() != null ? c.maxTipLength() : d.maxTipLength(),
                c.qualityTrimThreshold() != null ? c.qualityTrimThreshold() : d.qualityTrimThreshold(),
                c.minReadLength() != null ? c.minReadLength() : d.minReadLength());
        filled.validate();
        return filled;
    }

    /** 校验参数取值范围；非法参数抛出 IllegalArgumentException。 */
    public void validate() {
        if (kmerSize == null || kmerSize < 3 || kmerSize > 31 || (kmerSize & 1) == 0) {
            throw new IllegalArgumentException(
                    "kmerSize must be an odd number in range 3..31, got: " + kmerSize);
        }
        if (minCoverage == null || minCoverage < 1) {
            throw new IllegalArgumentException(
                    "minCoverage must be >= 1, got: " + minCoverage);
        }
        if (maxTipLength == null || maxTipLength < 0) {
            throw new IllegalArgumentException(
                    "maxTipLength must be >= 0, got: " + maxTipLength);
        }
        if (qualityTrimThreshold == null || qualityTrimThreshold < 0 || qualityTrimThreshold > 93) {
            throw new IllegalArgumentException(
                    "qualityTrimThreshold must be in 0..93 (Phred), got: " + qualityTrimThreshold);
        }
        if (minReadLength == null || minReadLength < kmerSize) {
            throw new IllegalArgumentException(
                    "minReadLength must be >= kmerSize (" + kmerSize + "), got: " + minReadLength);
        }
    }

}
