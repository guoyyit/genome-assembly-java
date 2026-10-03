package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 任务管理器：负责任务创建、按 ID 查找、并发访问与启动时的崩溃恢复。
 * 每个任务独占自己的目录、存储与内存状态，任务之间互不共享可变数据。
 */
public class TaskManager {

    private static final String TASK_DIR_PREFIX = "task-";

    private final Path dataDirectory;
    private final ConcurrentHashMap<String, AssemblyTask> tasks = new ConcurrentHashMap<>();

    public TaskManager(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data directory: " + dataDirectory, e);
        }
        recover();
    }

    public AssemblyTask createTask(String requestedId, AssemblyConfig config) {
        String taskId = (requestedId == null || requestedId.isBlank())
                ? UUID.randomUUID().toString()
                : requestedId;
        AssemblyTask existing = tasks.get(taskId);
        if (existing != null) {
            throw new IllegalArgumentException("task already exists: " + taskId);
        }
        synchronized (tasks) {
            if (tasks.containsKey(taskId)) {
                throw new IllegalArgumentException("task already exists: " + taskId);
            }
            Path taskDir = dataDirectory.resolve(TASK_DIR_PREFIX + taskId);
            TaskStore store = TaskStore.initialize(taskDir, config);
            AssemblyTask created = new AssemblyTask(taskId, store);
            tasks.put(taskId, created);
            return created;
        }
    }

    public AssemblyTask getTask(String taskId) {
        AssemblyTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return task;
    }

    public boolean exists(String taskId) {
        return tasks.containsKey(taskId);
    }

    public List<TaskSnapshot> listSnapshots() {
        return tasks.values().stream()
                .map(AssemblyTask::snapshot)
                .sorted(Comparator.comparing(TaskSnapshot::taskId))
                .toList();
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public void close() {
    }

    private void recover() {
        try (Stream<Path> dirs = Files.list(dataDirectory)) {
            List<Path> taskDirs = dirs
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(TASK_DIR_PREFIX))
                    .sorted()
                    .toList();
            for (Path taskDir : taskDirs) {
                String taskId = taskDir.getFileName().toString()
                        .substring(TASK_DIR_PREFIX.length());
                TaskStore store = TaskStore.open(taskDir);
                tasks.put(taskId, new AssemblyTask(taskId, store));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to recover tasks from " + dataDirectory, e);
        }
    }
}
