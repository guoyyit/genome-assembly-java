package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;

/**
 * 单个组装任务：拥有独立的引擎状态、批次集合与锁，任务间不共享可变状态。
 */
public class AssemblyTask {

    public AssemblyTask(String taskId, AssemblyConfig config) {
    }

    public String getTaskId() {
        return null;
    }

    public AssemblyConfig getConfig() {
        return null;
    }

    public TaskSnapshot submitBatch(ReadBatch batch) {
        return null;
    }

    public TaskSnapshot assemble() {
        return null;
    }

    public TaskSnapshot snapshot() {
        return null;
    }

    public AssemblyResult getResult() {
        return null;
    }
}
