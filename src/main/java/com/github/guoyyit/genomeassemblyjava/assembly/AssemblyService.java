package com.github.guoyyit.genomeassemblyjava.assembly;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Manages the lifecycle of assembly tasks. Tasks are fully independent of each other.
 */
@Service
public class AssemblyService {

    public AssemblyTask createTask(AssemblyParams params) {
        throw new UnsupportedOperationException("not implemented");
    }

    public AssemblyTask getTask(String taskId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public List<AssemblyTask> listTasks() {
        return List.of();
    }
}
