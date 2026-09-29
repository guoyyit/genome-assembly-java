package com.github.guoyyit.genomeassemblyjava.persist;

import com.github.guoyyit.genomeassemblyjava.assembly.AssemblyParams;
import com.github.guoyyit.genomeassemblyjava.assembly.TaskStatus;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Durable per-task storage. Each task owns a directory holding its metadata and an
 * atomically replaced state snapshot, so a restart never duplicates or loses reads.
 */
public final class TaskStore {

    private static final int MAGIC = 0x47414A41; // "GAJA"
    private static final int VERSION = 1;
    private static final String STATE_FILE = "state.bin";
    private static final String STATE_TMP = "state.bin.tmp";

    private final Path rootDir;

    public TaskStore(Path rootDir) {
        this.rootDir = rootDir;
    }

    public Path rootDir() {
        return rootDir;
    }

    /**
     * Complete durable state of one task. Callers pass freshly created collections.
     */
    public record TaskState(
            AssemblyParams params,
            TaskStatus status,
            long readsApplied,
            List<String> batchIds,
            Map<Long, Integer> counts) {
    }

    /**
     * Atomically replaces the task's durable state: the snapshot is written to a
     * temporary file and then moved into place, so a crash can never leave a
     * half-written state behind.
     */
    public synchronized void save(String taskId, TaskState state) {
        try {
            Path dir = taskDir(taskId);
            Files.createDirectories(dir);
            Path tmp = dir.resolve(STATE_TMP);
            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                AssemblyParams params = state.params();
                out.writeInt(params.k());
                out.writeInt(params.minKmerCount());
                out.writeInt(params.minBaseQuality());
                out.writeInt(params.tipLength());
                out.writeDouble(params.tipCoverageRatio());
                out.writeInt(params.maxBubbleLength());
                out.writeDouble(params.bubbleCoverageRatio());
                out.writeInt(params.minContigLength());
                out.writeUTF(state.status().name());
                out.writeLong(state.readsApplied());
                out.writeInt(state.batchIds().size());
                for (String batchId : state.batchIds()) {
                    out.writeUTF(batchId);
                }
                out.writeInt(state.counts().size());
                for (Map.Entry<Long, Integer> entry : state.counts().entrySet()) {
                    out.writeLong(entry.getKey());
                    out.writeInt(entry.getValue());
                }
            }
            Files.move(tmp, dir.resolve(STATE_FILE),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to save state of task " + taskId, e);
        }
    }

    /**
     * Loads the durable state of every task found under the root directory.
     * Tasks are returned ordered by task id for deterministic recovery.
     */
    public synchronized Map<String, TaskState> loadAll() {
        Map<String, TaskState> states = new TreeMap<>();
        if (!Files.isDirectory(rootDir)) {
            return states;
        }
        try (var stream = Files.list(rootDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path stateFile = dir.resolve(STATE_FILE);
                if (Files.isRegularFile(stateFile)) {
                    states.put(dir.getFileName().toString(), readState(stateFile));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan task store " + rootDir, e);
        }
        return states;
    }

    private TaskState readState(Path stateFile) {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(stateFile)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("unrecognized state file " + stateFile);
            }
            AssemblyParams params = new AssemblyParams(
                    in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readDouble(), in.readInt(), in.readDouble(), in.readInt());
            TaskStatus status = TaskStatus.valueOf(in.readUTF());
            long readsApplied = in.readLong();
            int batchCount = in.readInt();
            List<String> batchIds = new ArrayList<>(batchCount);
            for (int i = 0; i < batchCount; i++) {
                batchIds.add(in.readUTF());
            }
            int kmerCount = in.readInt();
            Map<Long, Integer> counts = new HashMap<>(kmerCount * 2);
            for (int i = 0; i < kmerCount; i++) {
                counts.put(in.readLong(), in.readInt());
            }
            return new TaskState(params, status, readsApplied, batchIds, counts);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read state file " + stateFile, e);
        }
    }

    private Path taskDir(String taskId) {
        if (!taskId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("unsafe task id: " + taskId);
        }
        return rootDir.resolve(taskId);
    }
}
