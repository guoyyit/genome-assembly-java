package com.github.guoyyit.genomeassemblyjava.storage;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;

import java.nio.file.Path;
import java.util.List;

/**
 * 单任务的持久化存储（WAL 语义）：批次文件原子落盘后才算受理，
 * 重启时重放全部批次，批次 ID 去重保证不重复、不遗漏。
 */
public class TaskStore {

    public TaskStore(Path taskDir, AssemblyConfig config) {
    }

    public static TaskStore initialize(Path taskDir, AssemblyConfig config) {
        return null;
    }

    public static TaskStore open(Path taskDir) {
        return null;
    }

    public Path getTaskDir() {
        return null;
    }

    public AssemblyConfig getConfig() {
        return null;
    }

    public void writeBatch(ReadBatch batch) {
    }

    public List<ReadBatch> loadBatches() {
        return List.of();
    }
}
