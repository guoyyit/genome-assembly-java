package com.github.guoyyit.genomeassemblyjava.tests.storage;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.storage.TaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskStoreRecoveryTest {

    private static final AssemblyConfig CONFIG = AssemblyConfig.defaults();

    @Test
    void replaysBatchesAfterRestartWithoutDuplication() throws Exception {
        Path root = Files.createTempDirectory("task-store");
        Path taskDir = root.resolve("task-1");

        TaskStore first = TaskStore.initialize(taskDir, CONFIG);
        assertTrue(first.writeBatch(batch("batch-A", "read-1", "ACGTACGTACGTACGTACGTACGT")));
        assertTrue(first.writeBatch(batch("batch-B", "read-2", "TTTTACGTACGTACGTACGTACGT")));
        assertFalse(first.writeBatch(batch("batch-A", "read-1", "ACGTACGTACGTACGTACGTACGT")),
                "duplicate batch must be reported as not newly accepted");

        TaskStore reopened = TaskStore.open(taskDir);
        assertEquals(CONFIG, reopened.getConfig());
        List<ReadBatch> replayed = reopened.loadBatches();
        assertEquals(List.of("batch-A", "batch-B"),
                replayed.stream().map(ReadBatch::batchId).toList());

        assertFalse(reopened.writeBatch(batch("batch-B", "read-2", "TTTTACGTACGTACGTACGTACGT")),
                "duplicate batch after restart must not be stored twice");
        assertTrue(reopened.writeBatch(batch("batch-C", "read-3", "GGGGACGTACGTACGTACGTACGT")));
        assertEquals(3, reopened.loadBatches().size());
    }

    @Test
    void tempFilesAreCleanedUpAfterHardStop() throws Exception {
        Path root = Files.createTempDirectory("task-store-crash");
        Path taskDir = root.resolve("task-2");
        TaskStore store = TaskStore.initialize(taskDir, CONFIG);
        store.writeBatch(batch("ok", "r1", "ACGTACGTACGTACGTACGTACGT"));
        Files.createFile(taskDir.resolve("batches").resolve("0000000000000007-lost.json.tmp"));

        TaskStore reopened = TaskStore.open(taskDir);
        List<ReadBatch> batches = reopened.loadBatches();
        assertEquals(1, batches.size());
        assertEquals("ok", batches.get(0).batchId());
        try (var stream = Files.list(taskDir.resolve("batches"))) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static ReadBatch batch(String batchId, String readId, String bases) {
        String qualities = "I".repeat(bases.length());
        return new ReadBatch(batchId,
                List.of(new SequenceRead(readId, bases, qualities)));
    }
}
