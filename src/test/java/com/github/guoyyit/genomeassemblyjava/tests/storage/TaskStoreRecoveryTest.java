package com.github.guoyyit.genomeassemblyjava.tests.storage;

import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStoreRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysBatchesAfterRestartWithoutDuplication() throws Exception {
        Path taskDir = tempDir.resolve("task-1");
        AssemblyConfig config = AssemblyConfig.defaults();
        TaskStore store = TaskStore.initialize(taskDir, config);

        ReadBatch batch1 = new ReadBatch("batch-1", List.of(
                new SequenceRead("read-1", "ACGTACGTACGTACGTACGTACG", null),
                new SequenceRead("read-2", "TGCATGCATGCATGCATGCATGCA", null)));
        ReadBatch batch2 = new ReadBatch("batch-2", List.of(
                new SequenceRead("read-3", "TTTTACGTACGTACGTACGTACG", null)));

        assertTrue(store.writeBatch(batch1));
        assertTrue(store.writeBatch(batch2));
        // 重复提交：返回 false，不产生新文件
        assertFalse(store.writeBatch(batch1));

        // 模拟进程重启：重新打开目录
        TaskStore reopened = TaskStore.open(taskDir);
        List<ReadBatch> replayed = reopened.loadBatches();

        assertEquals(2, replayed.size(), "duplicate batch must not be replayed");
        assertEquals("batch-1", replayed.get(0).batchId());
        assertEquals("batch-2", replayed.get(1).batchId());
        assertEquals(2, replayed.get(0).reads().size());

        // 重放后的组装结论必须与从未中断的情况完全一致
        KmerAssemblyEngine continuous = new KmerAssemblyEngine(config);
        continuous.addReads(batch1.reads());
        continuous.addReads(batch2.reads());

        KmerAssemblyEngine recovered = new KmerAssemblyEngine(config);
        for (ReadBatch batch : replayed) {
            recovered.addReads(batch.reads());
        }

        assertEquals(continuous.assemble(3, 2), recovered.assemble(3, 2),
                "recovered assembly must match continuous assembly");
    }
}
