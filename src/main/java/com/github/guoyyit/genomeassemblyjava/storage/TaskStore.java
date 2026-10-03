package com.github.guoyyit.genomeassemblyjava.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 单任务的持久化存储（WAL 语义）：
 * 批次以「临时文件写入 + fsync + 原子 rename + 目录 fsync」落盘，落盘成功才算受理；
 * 重启时扫描 batches 目录、按序号重放全部批次，batchId 去重保证不重复、不遗漏。
 */
public class TaskStore {

    private static final String CONFIG_FILE = "config.json";
    private static final String BATCHES_DIR = "batches";
    private static final String TMP_SUFFIX = ".tmp";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path taskDir;
    private final Path batchesDir;
    private final AssemblyConfig config;
    private final Set<String> acceptedBatchIds = new HashSet<>();
    private long nextSequence;

    private TaskStore(Path taskDir, AssemblyConfig config) {
        this.taskDir = taskDir;
        this.batchesDir = taskDir.resolve(BATCHES_DIR);
        this.config = config;
    }

    public static TaskStore initialize(Path taskDir, AssemblyConfig config) {
        try {
            Files.createDirectories(taskDir);
            Path configPath = taskDir.resolve(CONFIG_FILE);
            if (Files.exists(configPath)) {
                throw new IllegalStateException("task directory already initialized: " + taskDir);
            }
            Path tmp = taskDir.resolve(CONFIG_FILE + TMP_SUFFIX);
            writeJson(tmp, config);
            atomicallyMove(tmp, configPath);
            Files.createDirectories(taskDir.resolve(BATCHES_DIR));
            TaskStore store = new TaskStore(taskDir, config);
            store.recover();
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to initialize task store: " + taskDir, e);
        }
    }

    public static TaskStore open(Path taskDir) {
        try {
            Path configPath = taskDir.resolve(CONFIG_FILE);
            if (!Files.isRegularFile(configPath)) {
                throw new IllegalStateException("task directory is not initialized: " + taskDir);
            }
            AssemblyConfig config = MAPPER.readValue(Files.readAllBytes(configPath),
                    AssemblyConfig.class);
            TaskStore store = new TaskStore(taskDir, config);
            store.recover();
            return store;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open task store: " + taskDir, e);
        }
    }

    public Path getTaskDir() {
        return taskDir;
    }

    public AssemblyConfig getConfig() {
        return config;
    }

    /**
     * 原子写入一个批次；返回 true 表示首次受理，false 表示该 batchId 已受理（幂等命中）。
     */
    public synchronized boolean writeBatch(ReadBatch batch) {
        if (acceptedBatchIds.contains(batch.batchId())) {
            return false;
        }
        long sequence = nextSequence++;
        String fileName = String.format("%016d-%s.json", sequence, batch.batchId());
        Path target = batchesDir.resolve(fileName);
        Path tmp = batchesDir.resolve(fileName + TMP_SUFFIX);
        writeJson(tmp, batch);
        atomicallyMove(tmp, target);
        acceptedBatchIds.add(batch.batchId());
        return true;
    }

    /**
     * 按受理序号读取全部批次；同 batchId 只保留序号最小的一份。
     */
    public synchronized List<ReadBatch> loadBatches() {
        List<Path> files = listBatchFiles();
        List<ReadBatch> batches = new ArrayList<>(files.size());
        Set<String> seen = new HashSet<>();
        for (Path file : files) {
            ReadBatch batch = readJson(file, ReadBatch.class);
            if (seen.add(batch.batchId())) {
                batches.add(batch);
            }
        }
        return batches;
    }

    public synchronized boolean isAccepted(String batchId) {
        return acceptedBatchIds.contains(batchId);
    }

    private void recover() throws IOException {
        Files.createDirectories(batchesDir);
        cleanupTempFiles();
        List<Path> files = listBatchFiles();
        long maxSequence = -1;
        for (Path file : files) {
            ReadBatch batch = readJson(file, ReadBatch.class);
            acceptedBatchIds.add(batch.batchId());
            maxSequence = Math.max(maxSequence, sequenceOf(file));
        }
        nextSequence = maxSequence + 1;
    }

    private List<Path> listBatchFiles() {
        try (Stream<Path> stream = Files.list(batchesDir)) {
            return stream
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".json") && !name.endsWith(TMP_SUFFIX);
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list batch files in " + batchesDir, e);
        }
    }

    private long sequenceOf(Path file) {
        String name = file.getFileName().toString();
        return Long.parseLong(name.substring(0, 16));
    }

    private void cleanupTempFiles() throws IOException {
        try (Stream<Path> stream = Files.list(batchesDir)) {
            for (Path file : stream.toList()) {
                if (file.getFileName().toString().endsWith(TMP_SUFFIX)) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    private static void writeJson(Path file, Object value) {
        try {
            byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write " + file, e);
        }
    }

    private static <T> T readJson(Path file, Class<T> type) {
        try {
            return MAPPER.readValue(Files.readAllBytes(file), type);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }

    private static void atomicallyMove(Path source, Path target) {
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            fsyncDirectory(target.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to atomically publish " + target, e);
        }
    }

    private static void fsyncDirectory(Path directory) throws IOException {
        try (var reader = Files.newDirectoryStream(directory)) {
            reader.iterator().forEachRemaining(ignored -> {
            });
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try (var channel = Files.newByteChannel(directory, StandardOpenOption.READ)) {
                if (channel instanceof java.nio.channels.FileChannel fileChannel) {
                    fileChannel.force(true);
                }
            }
        }
    }
}
