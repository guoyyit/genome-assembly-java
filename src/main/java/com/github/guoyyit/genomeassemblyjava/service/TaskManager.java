package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务管理器：负责任务的创建、并发访问、重启恢复与生命周期管理。
 *
 * <p>每个任务的数据位于 {@code <dataDir>/tasks/<taskId>/}。启动时扫描全部任务目录，
 * 重放批次到引擎；批次文件只增不改，因此恢复后状态与崩溃前完全一致。
 */
@Component
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

    private final Path dataDirectory;
    private final Map<String, AssemblyTask> tasks = new ConcurrentHashMap<>();

    public TaskManager(@Value("${genome.data-dir:data}") Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    @PostConstruct
    public void recover() {
        Path tasksDir = dataDirectory.resolve("tasks");
        if (!Files.isDirectory(tasksDir)) {
            return;
        }
        try (var stream = Files.list(tasksDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).sorted().toList();
            for (Path dir : dirs) {
                try {
                    TaskStore store = TaskStore.open(dir);
                    AssemblyTask task = new AssemblyTask(
                            dir.getFileName().toString(), store.getConfig(), store);
                    List<ReadBatch> batches = store.loadBatches();
                    task.replay(batches);
                    tasks.put(task.getTaskId(), task);
                    log.info("recovered task {} with {} batches", task.getTaskId(), batches.size());
                } catch (IOException e) {
                    log.warn("skip unreadable task directory: {}", dir, e);
                }
            }
        } catch (IOException e) {
            log.warn("failed to scan task directory: {}", tasksDir, e);
        }
    }

    /** 创建新任务并立即落盘配置。 */
    public AssemblyTask createTask(AssemblyConfig config) {
        AssemblyConfig resolved = AssemblyConfig.fillDefaults(config);
        String taskId = UUID.randomUUID().toString();
        try {
            TaskStore store = TaskStore.initialize(
                    dataDirectory.resolve("tasks").resolve(taskId), resolved);
            AssemblyTask task = new AssemblyTask(taskId, resolved, store);
            tasks.put(taskId, task);
            return task;
        } catch (IOException e) {
            throw new IllegalStateException("failed to create task storage: " + taskId, e);
        }
    }

    public AssemblyTask getTask(String taskId) {
        AssemblyTask task = tasks.get(taskId);
        if (task == null) {
            throw new TaskNotFoundException(taskId);
        }
        return task;
    }

    public boolean taskExists(String taskId) {
        return tasks.containsKey(taskId);
    }

    public List<TaskSnapshot> listSnapshots() {
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (AssemblyTask task : tasks.values()) {
            snapshots.add(task.snapshot());
        }
        snapshots.sort(Comparator.comparing(TaskSnapshot::taskId));
        return snapshots;
    }

    @PreDestroy
    public void close() {
        // 批次在受理时已原子落盘，无内存态需要刷新。
    }
}
