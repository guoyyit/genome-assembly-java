package com.github.guoyyit.genomeassemblyjava.service;

import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyStats;
import com.github.guoyyit.genomeassemblyjava.model.BatchReceipt;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.model.TaskState;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个组装任务：拥有独立的引擎状态、批次集合与监视器锁，任务间不共享可变状态。
 *
 * <p>所有读操作在任务锁上复制不可变快照后返回，因此任务执行期间的查询始终是
 * 某个时刻的一致状态；批次提交先持久化、后更新内存，满足崩溃恢复的「不重不漏」。</p>
 */
public class AssemblyTask {

    private final String taskId;
    private final TaskStore store;

    private final List<ReadBatch> acceptedBatches = new ArrayList<>();
    private long readCount;
    private TaskState state = TaskState.CREATED;
    private AssemblyResult result;
    private String error;

    public AssemblyTask(String taskId, TaskStore store) {
        this.taskId = taskId;
        this.store = store;
        for (ReadBatch batch : store.loadBatches()) {
            ingest(batch);
        }
    }

    public String getTaskId() {
        return taskId;
    }

    public AssemblyConfig getConfig() {
        return store.getConfig();
    }

    /**
     * 受理一个已校验的批次；先 WAL 落盘再进入内存。重复 batchId 返回 duplicate=true
     * 且不改变任何计数。新数据到达后旧结论失效，状态回到 CREATED 等待重新组装。
     */
    public synchronized BatchReceipt submitBatch(ReadBatch batch) {
        if (state == TaskState.RUNNING) {
            throw new IllegalStateException("task is currently assembling: " + taskId);
        }
        boolean newlyAccepted = store.writeBatch(batch);
        if (!newlyAccepted) {
            return new BatchReceipt(taskId, batch.batchId(), true, snapshotLocked());
        }
        ingest(batch);
        if (state == TaskState.COMPLETED) {
            state = TaskState.CREATED;
            result = null;
        }
        return new BatchReceipt(taskId, batch.batchId(), false, snapshotLocked());
    }

    /**
     * 同步执行组装：全部已受理批次参与计算，返回完成时刻的一致快照。
     */
    public synchronized TaskSnapshot assemble() {
        if (state == TaskState.RUNNING) {
            throw new IllegalStateException("task is already assembling: " + taskId);
        }
        state = TaskState.RUNNING;
        error = null;
        List<SequenceRead> reads = new ArrayList<>();
        for (ReadBatch batch : acceptedBatches) {
            reads.addAll(batch.reads());
        }
        long batches = acceptedBatches.size();
        long readsTotal = readCount;
        try {
            result = KmerAssemblyEngine.assembleOnce(store.getConfig(), reads, readsTotal, batches);
            state = TaskState.COMPLETED;
        } catch (RuntimeException e) {
            state = TaskState.FAILED;
            error = e.getMessage();
        }
        return snapshotLocked();
    }

    public synchronized TaskSnapshot snapshot() {
        return snapshotLocked();
    }

    public synchronized AssemblyResult getResult() {
        return result;
    }

    public synchronized boolean isCompleted() {
        return state == TaskState.COMPLETED;
    }

    private void ingest(ReadBatch batch) {
        acceptedBatches.add(batch);
        readCount += batch.reads().size();
    }

    private TaskSnapshot snapshotLocked() {
        AssemblyStats stats = result == null ? AssemblyStats.EMPTY : result.stats();
        return new TaskSnapshot(taskId, state, readCount, acceptedBatches.size(),
                store.getConfig(), stats, error);
    }
}
