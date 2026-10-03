package com.github.guoyyit.genomeassemblyjava.tests.service;

import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.model.TaskState;
import com.github.guoyyit.genomeassemblyjava.service.AssemblyTask;
import com.github.guoyyit.genomeassemblyjava.service.TaskManager;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskConcurrencyTest {

    @TempDir
    Path tempDir;

    private TaskManager manager;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(tempDir);
        manager.recover();
        pool = Executors.newFixedThreadPool(16);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        manager.close();
    }

    @Test
    void concurrentTasksDoNotInterfere() throws Exception {
        int taskCount = 4;
        List<AssemblyTask> tasks = new ArrayList<>();
        List<List<SequenceRead>> taskReads = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            AssemblyTask task = manager.createTask(AssemblyConfig.defaults());
            tasks.add(task);
            Random random = new Random(100 + i);
            String reference = SequencingSimulator.randomReference(random, 1000);
            List<SequenceRead> reads =
                    SequencingSimulator.generateReads(reference, 100, 20, 0.01, random);
            taskReads.add(reads);
        }

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                AssemblyTask task = tasks.get(index);
                List<SequenceRead> reads = taskReads.get(index);
                for (int b = 0; b < 5; b++) {
                    int from = b * reads.size() / 5;
                    int to = (b + 1) * reads.size() / 5;
                    task.submitBatch(new ReadBatch("b-" + b, reads.subList(from, to)));
                }
                task.assemble();
            }));
        }
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }

        // 每个任务的结论必须与"只组装自己的读段"完全一致，任务间互不影响
        for (int i = 0; i < taskCount; i++) {
            AssemblyTask task = tasks.get(i);
            List<SequenceRead> reads = taskReads.get(i);
            KmerAssemblyEngine expected = new KmerAssemblyEngine(AssemblyConfig.defaults());
            expected.addReads(reads);
            assertEquals(expected.assemble(reads.size(), 5), task.getResult(),
                    "task " + i + " result must be independent of other tasks");
        }
    }

    @Test
    void queriesReturnConsistentSnapshots() throws Exception {
        AssemblyTask task = manager.createTask(AssemblyConfig.defaults());
        Random random = new Random(4);
        String reference = SequencingSimulator.randomReference(random, 1000);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 30, 0.01, random);
        int batchCount = 10;
        int[] bounds = partition(reads.size(), batchCount);

        AtomicBoolean stop = new AtomicBoolean(false);
        List<Future<?>> submitFutures = new ArrayList<>();

        // 并发查询：任何时刻快照都必须自洽
        for (int q = 0; q < 4; q++) {
            pool.submit(() -> {
                while (!stop.get()) {
                    TaskSnapshot snapshot = task.snapshot();
                    assertTrue(snapshot.readCount() >= 0);
                    assertTrue(snapshot.batchCount() >= 0);
                    assertTrue(snapshot.readCount() <= reads.size(),
                            "readCount must never exceed submitted reads");
                    assertTrue(snapshot.batchCount() <= batchCount,
                            "batchCount must never exceed submitted batches");
                    if (snapshot.state() == TaskState.COMPLETED) {
                        assertEquals(reads.size(), snapshot.readCount());
                        assertEquals(batchCount, snapshot.batchCount());
                    }
                }
            });
        }

        // 并发提交批次（含同一批次的重复提交）
        for (int b = 0; b < batchCount; b++) {
            int index = b;
            submitFutures.add(pool.submit(() -> {
                int from = bounds[index];
                int to = bounds[index + 1];
                try {
                    task.submitBatch(new ReadBatch("b-" + index, reads.subList(from, to)));
                } catch (com.github.guoyyit.genomeassemblyjava.service.DuplicateBatchException e) {
                    // 重复提交被拒绝是预期行为
                }
                // 故意重复提交同一批次
                try {
                    task.submitBatch(new ReadBatch("b-" + index, reads.subList(from, to)));
                } catch (com.github.guoyyit.genomeassemblyjava.service.DuplicateBatchException e) {
                    // 预期：重复提交只计入一次
                }
            }));
        }

        // 只等待提交线程完成；查询线程由 stop 标志在组装后停止
        for (Future<?> future : submitFutures) {
            future.get(60, TimeUnit.SECONDS);
        }
        task.assemble();
        stop.set(true);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        TaskSnapshot finalSnapshot = task.snapshot();
        assertEquals(TaskState.COMPLETED, finalSnapshot.state());
        assertEquals(reads.size(), finalSnapshot.readCount(),
                "no reads lost or duplicated under concurrent submission");
        assertEquals(batchCount, finalSnapshot.batchCount(),
                "no batches lost or duplicated under concurrent submission");
        assertTrue(finalSnapshot.stats().contigCount() >= 1);
    }

    private static int[] partition(int total, int parts) {
        int[] bounds = new int[parts + 1];
        for (int i = 0; i <= parts; i++) {
            bounds[i] = (int) ((long) total * i / parts);
        }
        return bounds;
    }
}
