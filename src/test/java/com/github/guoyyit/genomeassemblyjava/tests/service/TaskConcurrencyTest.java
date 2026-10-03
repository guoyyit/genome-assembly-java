package com.github.guoyyit.genomeassemblyjava.tests.service;

import com.github.guoyyit.genomeassemblyjava.model.BatchReceipt;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.model.TaskState;
import com.github.guoyyit.genomeassemblyjava.service.AssemblyTask;
import com.github.guoyyit.genomeassemblyjava.service.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskConcurrencyTest {

    private static final int READS_PER_BATCH = 25;

    @TempDir
    Path tempDir;

    @Test
    void concurrentTasksDoNotInterfere() throws Exception {
        TaskManager manager = new TaskManager(tempDir);
        AssemblyTask first = manager.createTask("task-A",
                new com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig(
                        11, 2, 22, 0, 11));
        AssemblyTask second = manager.createTask("task-B",
                new com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig(
                        15, 2, 30, 0, 15));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 40; i++) {
            int index = i;
            pool.submit(() -> {
                await(start);
                AssemblyTask task = index % 2 == 0 ? first : second;
                task.submitBatch(batch("batch-" + index, index));
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(20, first.snapshot().batchCount());
        assertEquals(20, second.snapshot().batchCount());
        assertEquals(20L * READS_PER_BATCH, first.snapshot().readCount());
        assertEquals(20L * READS_PER_BATCH, second.snapshot().readCount());

        TaskSnapshot snapshotA = first.assemble();
        TaskSnapshot snapshotB = second.assemble();
        assertEquals(TaskState.COMPLETED, snapshotA.state());
        assertEquals(TaskState.COMPLETED, snapshotB.state());
        assertEquals(11, first.getResult().config().kmerSize());
        assertEquals(15, second.getResult().config().kmerSize());
        manager.close();
    }

    @Test
    void queriesReturnConsistentSnapshots() throws Exception {
        TaskManager manager = new TaskManager(tempDir.resolve("nested"));
        AssemblyTask task = manager.createTask("task-C",
                com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig.defaults());

        ExecutorService writers = Executors.newFixedThreadPool(6);
        ExecutorService readers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger uniqueBatches = new AtomicInteger();
        for (int i = 0; i < 120; i++) {
            int index = i;
            writers.submit(() -> {
                await(start);
                String batchId = (index % 40 == 0) ? "shared-batch" : "batch-" + index;
                BatchReceipt receipt = task.submitBatch(
                        batch(batchId, "shared-batch".equals(batchId) ? 0 : index));
                if (!receipt.duplicate()) {
                    uniqueBatches.incrementAndGet();
                }
            });
        }
        for (int i = 0; i < 120; i++) {
            readers.submit(() -> {
                await(start);
                TaskSnapshot snapshot = task.snapshot();
                assertEquals(snapshot.readCount(),
                        snapshot.batchCount() * (long) READS_PER_BATCH,
                        "snapshot must always be internally consistent");
                assertTrue(snapshot.readCount() >= 0);
            });
        }
        start.countDown();
        writers.shutdown();
        readers.shutdown();
        assertTrue(writers.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(readers.awaitTermination(30, TimeUnit.SECONDS));

        TaskSnapshot finalSnapshot = task.snapshot();
        assertEquals(uniqueBatches.get(), finalSnapshot.batchCount());
        assertEquals(uniqueBatches.get() * (long) READS_PER_BATCH,
                finalSnapshot.readCount());
        TaskSnapshot assembled = task.assemble();
        assertEquals(TaskState.COMPLETED, assembled.state());
        manager.close();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static ReadBatch batch(String batchId, int seed) {
        char[] pattern = {'A', 'C', 'G', 'T'};
        List<SequenceRead> reads = new ArrayList<>(READS_PER_BATCH);
        for (int i = 0; i < READS_PER_BATCH; i++) {
            StringBuilder bases = new StringBuilder();
            for (int j = 0; j < 40; j++) {
                bases.append(pattern[(seed + i + j) & 3]);
            }
            reads.add(new SequenceRead(batchId + "-r" + i, bases.toString(),
                    "I".repeat(40)));
        }
        return new ReadBatch(batchId, reads);
    }
}
