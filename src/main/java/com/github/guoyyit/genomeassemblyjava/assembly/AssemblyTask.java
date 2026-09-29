package com.github.guoyyit.genomeassemblyjava.assembly;

import com.github.guoyyit.genomeassemblyjava.persist.TaskStore;

import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One assembly task. Owns its engine and its lock; no mutable state is shared
 * between tasks, so concurrent tasks cannot interfere.
 */
public final class AssemblyTask {

    private final String id;
    private final AssemblyParams params;
    private final AssemblyEngine engine;
    private final TaskStore store;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile TaskStatus status = TaskStatus.ACTIVE;

    private AssemblyTask(String id, AssemblyParams params, TaskStore store) {
        this.id = id;
        this.params = params;
        this.store = store;
        this.engine = new AssemblyEngine(params);
    }

    /**
     * Creates a task and persists its initial empty state before returning it.
     */
    public static AssemblyTask create(String id, AssemblyParams params, TaskStore store) {
        AssemblyTask task = new AssemblyTask(id, params, store);
        task.persist();
        return task;
    }

    /**
     * Rebuilds a task from its durable state after a restart.
     */
    public static AssemblyTask recover(String id, TaskStore store, TaskStore.TaskState state) {
        AssemblyTask task = new AssemblyTask(id, state.params(), store);
        task.engine.restore(state.readsApplied(), state.batchIds(), state.counts());
        task.status = state.status();
        return task;
    }

    public String id() {
        return id;
    }

    public AssemblyParams params() {
        return params;
    }

    public TaskStatus status() {
        return status;
    }

    /**
     * Applies a batch and persists the new state before acknowledging. Returns false
     * when the batchId was already applied: the submission is then a harmless no-op,
     * which is what makes retries after a crash safe.
     */
    public boolean submit(String batchId, List<Read> reads) {
        lock.lock();
        try {
            if (status == TaskStatus.CLOSED) {
                throw new IllegalStateException("task " + id + " is closed");
            }
            boolean applied = engine.applyBatch(batchId, reads);
            if (applied) {
                persist();
            }
            return applied;
        } finally {
            lock.unlock();
        }
    }

    public TaskSnapshot snapshot() {
        lock.lock();
        try {
            return new TaskSnapshot(id, status, params, engine.readsApplied(),
                    engine.batchesApplied(), engine.distinctKmers());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Computes the assembly for the current state. The result is a pure function of
     * the applied reads, independent of their submission order.
     */
    public AssemblyResult result() {
        lock.lock();
        try {
            return engine.assemble();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Closes the task for new batches and persists the final status.
     */
    public TaskSnapshot close() {
        lock.lock();
        try {
            status = TaskStatus.CLOSED;
            persist();
            return new TaskSnapshot(id, status, params, engine.readsApplied(),
                    engine.batchesApplied(), engine.distinctKmers());
        } finally {
            lock.unlock();
        }
    }

    private void persist() {
        store.save(id, new TaskStore.TaskState(params, status, engine.readsApplied(),
                engine.appliedBatchIds(), engine.exportCounts()));
    }
}
