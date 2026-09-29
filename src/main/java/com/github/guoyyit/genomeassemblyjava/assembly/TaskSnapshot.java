package com.github.guoyyit.genomeassemblyjava.assembly;

/**
 * Consistent point-in-time view of a task, captured under the task lock so queries
 * during execution never observe a half-updated state.
 */
public record TaskSnapshot(
        String taskId,
        TaskStatus status,
        AssemblyParams params,
        long readsApplied,
        int batchesApplied,
        long distinctKmers) {
}
