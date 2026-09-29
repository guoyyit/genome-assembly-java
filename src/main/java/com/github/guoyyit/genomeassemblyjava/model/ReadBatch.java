package com.github.guoyyit.genomeassemblyjava.model;

import java.util.List;

/**
 * 一个提交批次；batchId 用于幂等去重，保证恢复时不重复处理。
 */
public record ReadBatch(String batchId, List<SequenceRead> reads) {
}
