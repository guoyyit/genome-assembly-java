package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 批次受理回执：duplicate=false 表示首次受理，true 表示 batchId 命中幂等去重，
 * 调用方可据此区分「新批次」与「重复批次」，两种情况下数据都只计一次。
 */
public record BatchReceipt(String taskId, String batchId, boolean duplicate,
                           TaskSnapshot snapshot) {
}
