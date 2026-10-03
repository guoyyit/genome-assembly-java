package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyStats;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.model.TaskState;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个组装任务：拥有独立的引擎状态、批次集合与锁，任务间不共享可变状态。
 *
 * <p>所有状态变更（受理批次、触发组装）都在同一把锁内进行；快照在锁内构造，
 * 保证查询方看到的是某个时刻的一致状态，不会读到半更新数据。
 */
public class AssemblyTask {

    private final String taskId;
    private final AssemblyConfig config;
    private final TaskStore store;
    private final KmerAssemblyEngine engine;
    private final ReentrantLock lock = new ReentrantLock();

    private volatile TaskState state = TaskState.CREATED;
    private long readCount;
    private long batchCount;
    private volatile AssemblyResult result;

    public AssemblyTask(String taskId, AssemblyConfig config, TaskStore store) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.engine = new KmerAssemblyEngine(config);
    }

    public String getTaskId() {
        return taskId;
    }

    public AssemblyConfig getConfig() {
        return config;
    }

    /** 重放批次到引擎（仅恢复时调用，调用前任务尚未发布）。 */
    void replay(Iterable<ReadBatch> batches) throws IOException {
        for (ReadBatch batch : batches) {
            engine.addReads(batch.reads());
            readCount += batch.reads().size();
            batchCount++;
        }
        if (batchCount > 0) {
            state = TaskState.RUNNING;
        }
    }

    /**
     * 受理一个批次：校验 -> 原子落盘 -> 计入引擎。
     * 重复 batchId 抛出 {@link DuplicateBatchException}，不产生任何副作用。
     */
    public TaskSnapshot submitBatch(ReadBatch batch) {
        validateBatch(batch);
        lock.lock();
        try {
            if (!store.writeBatch(batch)) {
                throw new DuplicateBatchException(batch.batchId());
            }
            engine.addReads(batch.reads());
            readCount += batch.reads().size();
            batchCount++;
            state = TaskState.RUNNING;
            return snapshotLocked();
        } catch (IOException e) {
            state = TaskState.FAILED;
            throw new IllegalStateException("failed to persist batch: " + batch.batchId(), e);
        } finally {
            lock.unlock();
        }
    }

    /** 触发组装，派生当前结论；结果只与已受理批次有关，与提交顺序/并发无关。 */
    public TaskSnapshot assemble() {
        lock.lock();
        try {
            result = engine.assemble(readCount, batchCount);
            state = TaskState.COMPLETED;
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    /** 返回当前时刻的不可变快照。 */
    public TaskSnapshot snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    public AssemblyResult getResult() {
        AssemblyResult r = result;
        if (r == null) {
            throw new IllegalStateException("task has not been assembled yet: " + taskId);
        }
        return r;
    }

    public boolean hasResult() {
        return result != null;
    }

    private TaskSnapshot snapshotLocked() {
        AssemblyStats stats = result == null ? AssemblyStats.EMPTY : result.stats();
        return new TaskSnapshot(taskId, state, readCount, batchCount, config, stats, null);
    }

    private static void validateBatch(ReadBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (batch.batchId() == null || batch.batchId().isBlank()) {
            throw new IllegalArgumentException("batchId must not be blank");
        }
        if (batch.reads() == null || batch.reads().isEmpty()) {
            throw new IllegalArgumentException("batch reads must not be empty: " + batch.batchId());
        }
        for (SequenceRead read : batch.reads()) {
            if (read == null) {
                throw new IllegalArgumentException("read must not be null in batch: " + batch.batchId());
            }
            if (read.id() == null || read.id().isBlank()) {
                throw new IllegalArgumentException("read id must not be blank in batch: " + batch.batchId());
            }
            if (read.bases() == null || read.bases().isBlank()) {
                throw new IllegalArgumentException(
                        "read bases must not be blank: " + read.id());
            }
            String bases = read.bases();
            for (int i = 0; i < bases.length(); i++) {
                char base = bases.charAt(i);
                if (base != 'A' && base != 'C' && base != 'G' && base != 'T'
                        && base != 'a' && base != 'c' && base != 'g' && base != 't') {
                    throw new IllegalArgumentException(
                            "illegal base '" + base + "' in read " + read.id());
                }
            }
            if (read.qualities() != null && read.qualities().length() != bases.length()) {
                throw new IllegalArgumentException(
                        "qualities length (" + read.qualities().length()
                                + ") does not match bases length (" + bases.length()
                                + ") in read " + read.id());
            }
        }
    }
}
