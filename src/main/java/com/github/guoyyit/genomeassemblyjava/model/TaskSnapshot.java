package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 某一时刻任务状态的不可变快照。
 */
public record TaskSnapshot(String taskId,
                           TaskState state,
                           long readCount,
                           long batchCount,
                           AssemblyConfig config,
                           AssemblyStats stats,
                           String error) {
}
