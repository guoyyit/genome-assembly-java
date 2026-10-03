package com.github.guoyyit.genomeassemblyjava.web;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;

/**
 * 创建任务请求体：所有字段均可缺省，缺省值取自 AssemblyConfig.defaults()。
 * JSON 字段结构与 AssemblyConfig 保持一致。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateTaskRequest(Integer kmerSize,
                                Integer minCoverage,
                                Integer maxTipLength,
                                Integer qualityTrimThreshold,
                                Integer minReadLength) {

    public AssemblyConfig toConfig() {
        AssemblyConfig defaults = AssemblyConfig.defaults();
        return new AssemblyConfig(
                kmerSize == null ? defaults.kmerSize() : kmerSize,
                minCoverage == null ? defaults.minCoverage() : minCoverage,
                maxTipLength == null ? defaults.maxTipLength() : maxTipLength,
                qualityTrimThreshold == null
                        ? defaults.qualityTrimThreshold() : qualityTrimThreshold,
                minReadLength == null ? defaults.minReadLength() : minReadLength);
    }
}
