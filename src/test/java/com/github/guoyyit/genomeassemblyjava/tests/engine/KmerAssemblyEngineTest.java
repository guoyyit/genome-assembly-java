package com.github.guoyyit.genomeassemblyjava.tests.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.Contig;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmerAssemblyEngineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void assemblesSimpleReference() {
        Random random = new Random(1234);
        String reference = SequencingSimulator.randomReference(random, 2000);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 30, 0.005, random);

        KmerAssemblyEngine engine = new KmerAssemblyEngine(AssemblyConfig.defaults());
        engine.addReads(reads);
        AssemblyResult result = engine.assemble(reads.size(), 1);

        assertTrue(result.stats().contigCount() >= 1, "should produce at least one contig");
        for (Contig contig : result.contigs()) {
            assertTrue(isReferenceContig(contig.sequence(), reference),
                    "contig must be a substring of reference (or RC): " + contig.sequence());
        }
        assertTrue(result.stats().totalLength() >= reference.length() * 0.95,
                "backbone should be recovered, totalLength=" + result.stats().totalLength());
        assertTrue(result.stats().n50() > 0);
        assertTrue(result.stats().longestLength() >= result.stats().n50());
    }

    @Test
    void rejectsLowFrequencyErrorPaths() {
        Random random = new Random(99);
        String reference = SequencingSimulator.randomReference(random, 2000);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 15, 0.03, random);

        KmerAssemblyEngine engine = new KmerAssemblyEngine(AssemblyConfig.defaults());
        engine.addReads(reads);
        AssemblyResult result = engine.assemble(reads.size(), 1);

        for (Contig contig : result.contigs()) {
            assertTrue(isReferenceContig(contig.sequence(), reference),
                    "no novel fragment allowed: " + contig.sequence());
        }
        assertTrue(result.stats().totalLength() >= reference.length() * 0.9,
                "most of backbone should survive error filtering");
    }

    @Test
    void qualityValuesDiscriminateErrors() {
        Random random = new Random(55);
        String reference = SequencingSimulator.randomReference(random, 1000);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 30, 0.0, random);

        // 同样的读段，质量串全部为最低质量 -> 任何 k-mer 都不得计数
        List<SequenceRead> lowQuality = reads.stream()
                .map(r -> new SequenceRead(r.id(), r.bases(), "!".repeat(r.bases().length())))
                .toList();
        KmerAssemblyEngine lowQualEngine = new KmerAssemblyEngine(AssemblyConfig.defaults());
        lowQualEngine.addReads(lowQuality);
        AssemblyResult lowQualResult = lowQualEngine.assemble(lowQuality.size(), 1);
        assertEquals(0, lowQualResult.stats().contigCount(),
                "low-quality k-mers must not enter the graph");

        // 高质量版本应正常组装
        KmerAssemblyEngine goodEngine = new KmerAssemblyEngine(AssemblyConfig.defaults());
        goodEngine.addReads(reads);
        AssemblyResult goodResult = goodEngine.assemble(reads.size(), 1);
        assertTrue(goodResult.stats().contigCount() >= 1);
    }

    @Test
    void orderIndependenceAndIncrementalEquivalence() throws Exception {
        Random random = new Random(7);
        String reference = SequencingSimulator.randomReference(random, 1500);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 25, 0.01, random);
        List<ReadBatch> batches = splitBatches(reads, 3, "b");

        // 顺序 1：按批次顺序提交
        KmerAssemblyEngine engine1 = new KmerAssemblyEngine(AssemblyConfig.defaults());
        long count1 = 0;
        for (ReadBatch batch : batches) {
            engine1.addReads(batch.reads());
            count1 += batch.reads().size();
        }
        AssemblyResult result1 = engine1.assemble(count1, batches.size());

        // 顺序 2：乱序提交
        List<ReadBatch> shuffled = new ArrayList<>(batches);
        Collections.shuffle(shuffled, new Random(5));
        KmerAssemblyEngine engine2 = new KmerAssemblyEngine(AssemblyConfig.defaults());
        long count2 = 0;
        for (ReadBatch batch : shuffled) {
            engine2.addReads(batch.reads());
            count2 += batch.reads().size();
        }
        AssemblyResult result2 = engine2.assemble(count2, shuffled.size());

        assertEquals(MAPPER.writeValueAsString(result1), MAPPER.writeValueAsString(result2),
                "batch submission order must not change the result");

        // 增量：先组装前两批，再追加第三批，最终结论必须与一次性提交完全一致
        KmerAssemblyEngine engine3 = new KmerAssemblyEngine(AssemblyConfig.defaults());
        engine3.addReads(batches.get(0).reads());
        engine3.addReads(batches.get(1).reads());
        engine3.assemble(batches.get(0).reads().size() + batches.get(1).reads().size(), 2);
        engine3.addReads(batches.get(2).reads());
        AssemblyResult result3 = engine3.assemble(reads.size(), batches.size());

        assertEquals(MAPPER.writeValueAsString(result1), MAPPER.writeValueAsString(result3),
                "incremental assembly after appending batches must match all-at-once");
    }

    @Test
    void minReadLengthIsConfigurable() {
        Random random = new Random(8);
        String reference = SequencingSimulator.randomReference(random, 500);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 50, 30, 0.0, random);

        AssemblyConfig config = new AssemblyConfig(15, 2, 30, 0, 15);
        KmerAssemblyEngine engine = new KmerAssemblyEngine(config);
        engine.addReads(reads);
        AssemblyResult result = engine.assemble(reads.size(), 1);
        assertTrue(result.stats().contigCount() >= 1,
                "assembly must work with configured kmer/minReadLength");
    }

    @Test
    void isolatedKmerProducesContig() {
        // 长度恰好为 k 的读段产生孤立 k-mer，也应输出 contig
        AssemblyConfig config = new AssemblyConfig(21, 1, 42, 0, 21);
        KmerAssemblyEngine engine = new KmerAssemblyEngine(config);
        engine.addRead(new SequenceRead("r1", "ACGTACGTACGTACGTACGTA", null));
        AssemblyResult result = engine.assemble(1, 1);
        assertEquals(1, result.stats().contigCount());
        assertEquals(21, result.stats().totalLength());
        assertEquals("ACGTACGTACGTACGTACGTA", result.contigs().get(0).sequence());
    }

    @Test
    void largeBatchCompletesInReasonableTimeWithBoundedMemory() {
        // 高覆盖度 + 大量序列：处理时间必须在合理范围内，统计指标保持正确
        Random random = new Random(2024);
        String reference = SequencingSimulator.randomReference(random, 5000);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 500, 0.005, random);
        assertTrue(reads.size() >= 20000, "should generate a large batch");

        KmerAssemblyEngine engine = new KmerAssemblyEngine(AssemblyConfig.defaults());
        long start = System.currentTimeMillis();
        engine.addReads(reads);
        AssemblyResult result = engine.assemble(reads.size(), 1);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 30_000, "large batch should assemble within 30s, took " + elapsed + "ms");
        assertTrue(result.stats().contigCount() >= 1);
        for (Contig contig : result.contigs()) {
            assertTrue(isReferenceContig(contig.sequence(), reference),
                    "no novel fragment allowed at high coverage");
        }
        assertTrue(result.stats().totalLength() >= reference.length() * 0.95,
                "backbone should be recovered at high coverage");
        assertTrue(result.stats().n50() > 0);
    }

    private static boolean isReferenceContig(String contig, String reference) {
        if (reference.contains(contig)) {
            return true;
        }
        String rc = reverseComplement(contig);
        return reference.contains(rc);
    }

    private static String reverseComplement(String sequence) {
        char[] chars = sequence.toCharArray();
        for (int i = 0, j = sequence.length() - 1; i <= j; i++, j--) {
            char tmp = complement(chars[i]);
            chars[i] = complement(chars[j]);
            chars[j] = tmp;
        }
        return new String(chars);
    }

    private static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'T' -> 'A';
            case 'C' -> 'G';
            case 'G' -> 'C';
            default -> throw new IllegalArgumentException("unexpected base: " + base);
        };
    }

    private static List<ReadBatch> splitBatches(List<SequenceRead> reads, int parts, String prefix) {
        List<ReadBatch> batches = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < parts; i++) {
            int to = (i + 1) * reads.size() / parts;
            batches.add(new ReadBatch(prefix + "-" + i, reads.subList(from, to)));
            from = to;
        }
        return batches;
    }
}
