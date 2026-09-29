package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;

import java.nio.file.Path;
import java.util.List;

/**
 * 任务管理器：负责任务的创建、并发访问、重启恢复与生命周期管理。
 */
public class TaskManager {

    public TaskManager(Path dataDirectory) {
    }

    public AssemblyTask createTask(String taskId, AssemblyConfig config) {
        return null;
    }

    public AssemblyTask getTask(String taskId) {
        return null;
    }
    public List<TaskSnapshot> listSnapshots() {
        return List.of();
    }

    public void close() {
    }
}
