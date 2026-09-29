package com.github.guoyyit.genomeassemblyjava.api;

/**
 * Task creation request. Null fields fall back to documented defaults.
 */
public record CreateTaskRequest(
        Integer k,
        Integer minKmerCount,
        Integer minBaseQuality,
        Integer tipLength,
        Double tipCoverageRatio,
        Integer maxBubbleLength,
        Double bubbleCoverageRatio,
        Integer minContigLength) {
}
