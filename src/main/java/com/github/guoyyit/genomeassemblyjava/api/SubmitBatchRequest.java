package com.github.guoyyit.genomeassemblyjava.api;

import com.github.guoyyit.genomeassemblyjava.assembly.Read;

import java.util.List;

/**
 * One batch of reads. batchId makes submission idempotent: resubmitting an already
 * applied batchId is a no-op, which is what makes crash recovery safe.
 */
public record SubmitBatchRequest(String batchId, List<Read> reads) {
}
