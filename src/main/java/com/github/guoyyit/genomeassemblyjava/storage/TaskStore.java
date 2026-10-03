package com.github.guoyyit.genomeassemblyjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 单任务的持久化存储（WAL 语义）。
 *
 * <p>目录结构：
 * <pre>
 *   taskDir/config.json          组装参数（创建时一次写入）
 *   taskDir/batches/000001.json  每个批次一个文件，原子落盘后才算受理
 * </pre>
 *
 * <p>批次文件按序号命名，重启时按序号重放；batchId 去重保证同一批次只计入一次，
 * 既不重复处理也不遗漏。批次内容只增不改，已受理数据不会被后续操作覆盖。
 */
public class TaskStore {

    private static final String CONFIG_FILE = "config.json";
    private static final String BATCH_DIR = "batches";

    private final Path taskDir;
    private final AssemblyConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<String> acceptedBatchIds = new HashSet<>();
    private int nextSequence = 1;

    private TaskStore(Path taskDir, AssemblyConfig config) {
        this.taskDir = taskDir;
        this.config = config;
    }

    /** 创建新任务目录并写入配置。 */
    public static TaskStore initialize(Path taskDir, AssemblyConfig config) throws IOException {
        Files.createDirectories(taskDir);
        TaskStore store = new TaskStore(taskDir, config);
        store.writeConfig(config);
        Files.createDirectories(store.batchDir());
        return store;
    }

    /** 打开已有任务目录，读取配置并扫描已受理批次。 */
    public static TaskStore open(Path taskDir) throws IOException {
        Path configFile = taskDir.resolve(CONFIG_FILE);
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("task directory is missing config.json: " + taskDir);
        }
        AssemblyConfig config = new ObjectMapper().readValue(configFile.toFile(), AssemblyConfig.class);
        TaskStore store = new TaskStore(taskDir, config);
        Files.createDirectories(store.batchDir());
        List<Path> files = store.listBatchFiles();
        for (Path file : files) {
            ReadBatch batch = new ObjectMapper().readValue(file.toFile(), ReadBatch.class);
            store.acceptedBatchIds.add(batch.batchId());
            store.nextSequence = Math.max(store.nextSequence, sequenceOf(file) + 1);
        }
        return store;
    }

    public Path getTaskDir() {
        return taskDir;
    }

    public AssemblyConfig getConfig() {
        return config;
    }

    public boolean isDuplicate(String batchId) {
        return acceptedBatchIds.contains(batchId);
    }

    /**
     * 原子写入批次文件。重复 batchId 返回 {@code false} 且不产生任何写操作；
     * 首次受理返回 {@code true}。
     */
    public synchronized boolean writeBatch(ReadBatch batch) throws IOException {
        if (acceptedBatchIds.contains(batch.batchId())) {
            return false;
        }
        Files.createDirectories(batchDir());
        Path target = batchDir().resolve(String.format("%06d.json", nextSequence));
        Path tmp = batchDir().resolve(String.format("%06d.json.tmp", nextSequence));
        objectMapper.writeValue(tmp.toFile(), batch);
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        acceptedBatchIds.add(batch.batchId());
        nextSequence++;
        return true;
    }

    /** 按受理顺序重放全部批次。 */
    public List<ReadBatch> loadBatches() throws IOException {
        List<ReadBatch> batches = new ArrayList<>();
        for (Path file : listBatchFiles()) {
            batches.add(objectMapper.readValue(file.toFile(), ReadBatch.class));
        }
        return batches;
    }

    private List<Path> listBatchFiles() throws IOException {
        Files.createDirectories(batchDir());
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(batchDir())) {
            stream.filter(p -> p.getFileName().toString().matches("\\d{6}\\.json"))
                    .sorted()
                    .forEach(files::add);
        }
        return files;
    }

    private Path batchDir() {
        return taskDir.resolve(BATCH_DIR);
    }

    private void writeConfig(AssemblyConfig config) throws IOException {
        Files.createDirectories(taskDir);
        Path tmp = taskDir.resolve(CONFIG_FILE + ".tmp");
        objectMapper.writeValue(tmp.toFile(), config);
        Path target = taskDir.resolve(CONFIG_FILE);
        try {
            Files.move(tmp, target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int sequenceOf(Path file) {
        String name = file.getFileName().toString();
        return Integer.parseInt(name.substring(0, 6));
    }
}
