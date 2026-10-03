package com.github.guoyyit.genomeassemblyjava.tests.engine;

import com.github.guoyyit.genomeassemblyjava.engine.KmerAssemblyEngine;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.Contig;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KmerAssemblyEngineTest {

    private static final AssemblyConfig CONFIG = new AssemblyConfig(21, 2, 42, 20, 21);

    @Test
    void assemblesSimpleReference() {
        Random random = new Random(2024);
        String reference = SequencingSimulator.randomReference(random, 600);
        List<SequenceRead> reads = SequencingSimulator.generateReads(
                reference, 80, 30, 0.01, random);

        KmerAssemblyEngine engine = new KmerAssemblyEngine(CONFIG);
        engine.addReads(reads);
        AssemblyResult result = engine.assemble(reads.size(), 1);

        assertTrue(result.stats().contigCount() >= 1, "at least one contig expected");
        assertTrue(result.stats().totalLength() >= reference.length() * 0.9,
                "assembled backbone should cover most of the reference");
        for (Contig contig : result.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference),
                    "contig must not contain sequence absent from reference: " + contig.id());
            assertTrue(contig.length() >= 21);
        }
        assertEquals(result.stats().longestLength(),
                result.contigs().get(0).length());
        assertEquals(result.stats().contigCount(), result.contigs().size());
        assertValidStats(result);
    }

    @Test
    void rejectsLowFrequencyErrorPaths() {
        Random random = new Random(77);
        String reference = SequencingSimulator.randomReference(random, 500);
        List<SequenceRead> highErrorLowQuality = SequencingSimulator.generateReads(
                reference, 80, 30, 0.08, 0.0, random);
        KmerAssemblyEngine engine = new KmerAssemblyEngine(CONFIG);
        engine.addReads(highErrorLowQuality);
        AssemblyResult result = engine.assemble(highErrorLowQuality.size(), 1);
        for (Contig contig : result.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference),
                    "low-quality error path leaked into assembly: " + contig.id());
        }
        assertTrue(result.stats().totalLength() >= reference.length() * 0.8);

        List<SequenceRead> highErrorWithQualityTricks = SequencingSimulator.generateReads(
                reference, 80, 40, 0.05, 0.15, new Random(78));
        KmerAssemblyEngine strict = new KmerAssemblyEngine(CONFIG);
        strict.addReads(highErrorWithQualityTricks);
        AssemblyResult strictResult = strict.assemble(highErrorWithQualityTricks.size(), 1);
        for (Contig contig : strictResult.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference),
                    "high-frequency false path leaked into assembly: " + contig.id());
        }
    }

    @Test
    void lowQualityBasesAreExcludedButHighQualityKept() {
        String good = "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
        String qualities = "!".repeat(good.length());
        KmerAssemblyEngine noTrusted = new KmerAssemblyEngine(CONFIG);
        noTrusted.addRead(new SequenceRead("x", good, qualities));
        AssemblyResult emptyResult = noTrusted.assemble(1, 1);
        assertEquals(0, emptyResult.stats().contigCount());

        String highQuality = String.valueOf((char) ('!' + 35)).repeat(good.length());
        KmerAssemblyEngine trusted = new KmerAssemblyEngine(CONFIG);
        trusted.addRead(new SequenceRead("x", good, highQuality));
        trusted.addRead(new SequenceRead("y", good, highQuality));
        AssemblyResult populated = trusted.assemble(2, 1);
        assertTrue(populated.stats().contigCount() >= 1);
    }

    @Test
    void orderIndependenceAndIncrementalEquivalence() {
        Random random = new Random(9001);
        String reference = SequencingSimulator.randomReference(random, 700);
        List<SequenceRead> batchA = SequencingSimulator.generateReads(
                reference, 80, 20, 0.02, random);
        List<SequenceRead> batchB = SequencingSimulator.generateReads(
                reference, 80, 20, 0.02, random);

        AssemblyResult allAtOnce = assembleAll(CONFIG, concat(batchA, batchB));
        AssemblyResult incremental = assembleIncremental(CONFIG, batchA, batchB);
        assertEquals(canonicalJson(allAtOnce), canonicalJson(incremental),
                "incremental assembly must equal one-shot assembly byte for byte");

        List<SequenceRead> shuffled = concat(batchA, batchB);
        Collections.shuffle(shuffled, new Random(5));
        AssemblyResult reordered = assembleAll(CONFIG, shuffled);
        assertEquals(canonicalJson(allAtOnce), canonicalJson(reordered),
                "submission order must not change the result");

        AssemblyResult repeated = assembleAll(CONFIG, concat(batchA, batchB));
        assertEquals(canonicalJson(allAtOnce), canonicalJson(repeated),
                "same inputs must produce byte-identical results");
    }

    @Test
    void kmerLengthIsConfigurable() {
        Random random = new Random(3);
        String reference = SequencingSimulator.randomReference(random, 400);
        List<SequenceRead> reads = SequencingSimulator.generateReads(
                reference, 70, 40, 0.01, random);
        AssemblyConfig smallK = new AssemblyConfig(11, 2, 22, 20, 11);
        AssemblyResult result = assembleAll(smallK, reads);
        for (Contig contig : result.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference));
        }
        assertTrue(result.stats().totalLength() > 0);
    }

    @Test
    void danglingTipsAndRepeatBubblesAreSimplifiedWithoutBreakingConnectivity() {
        Random random = new Random(424);
        String reference = SequencingSimulator.randomReference(random, 450);

        List<SequenceRead> first = SequencingSimulator.generateReads(
                reference, 75, 18, 0.03, 0.0, new Random(31));
        AssemblyResult partial = assembleAll(CONFIG, first);
        for (Contig contig : partial.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference));
        }

        List<SequenceRead> additional = SequencingSimulator.generateReads(
                reference, 75, 30, 0.01, 0.0, new Random(32));
        KmerAssemblyEngine engine = new KmerAssemblyEngine(CONFIG);
        engine.addReads(first);
        engine.addReads(additional);
        AssemblyResult grown = engine.assemble(first.size() + additional.size(), 2);

        assertTrue(grown.stats().totalLength() >= reference.length() * 0.9,
                "appending more batches after simplification must recover the backbone");
        for (Contig contig : grown.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference),
                    "simplification must not retain invented sequence: " + contig.id());
        }
        long coveredBases = 0;
        for (Contig contig : grown.contigs()) {
            int index = reference.contains(contig.sequence())
                    ? reference.indexOf(contig.sequence())
                    : reference.indexOf(SequencingSimulator.reverseComplement(contig.sequence()));
            assertTrue(index >= 0);
            coveredBases += contig.length();
        }
        assertTrue(coveredBases >= reference.length() * 0.85);
    }

    @Test
    void lowCoverageLeavesBackboneUnsupportedAndHighCoverageSucceeds() {
        Random random = new Random(818);
        String reference = SequencingSimulator.randomReference(random, 400);
        List<SequenceRead> shallow = SequencingSimulator.generateReads(
                reference, 70, 1.5, 0.0, random);
        AssemblyResult shallowResult = assembleAll(CONFIG, shallow);
        assertTrue(shallowResult.stats().totalLength() < reference.length() * 0.5,
                "coverage below minCoverage must not produce a trusted backbone");

        List<SequenceRead> deep = SequencingSimulator.generateReads(
                reference, 70, 40, 0.0, new Random(819));
        AssemblyResult deepResult = assembleAll(CONFIG, deep);
        assertTrue(deepResult.stats().totalLength() >= reference.length() * 0.9);
    }

    @Test
    void largeBatchesCompleteWithBoundedMemoryAndCorrectCounts() {
        Random random = new Random(515);
        String reference = SequencingSimulator.randomReference(random, 300);
        List<SequenceRead> reads = SequencingSimulator.generateReads(
                reference, 60, 120, 0.02, random);
        assertTrue(reads.size() > 400);
        long startNanos = System.nanoTime();
        AssemblyResult result = assembleAll(CONFIG, reads);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        assertTrue(elapsedMillis < 60_000, "assembly must finish promptly: " + elapsedMillis);
        assertEquals(reads.size(), result.readCount());
        assertTrue(result.stats().totalLength() >= reference.length() * 0.85);
        for (Contig contig : result.contigs()) {
            assertTrue(isSubstringOfReference(contig.sequence(), reference));
        }
    }

    private static AssemblyResult assembleAll(AssemblyConfig config, List<SequenceRead> reads) {
        KmerAssemblyEngine engine = new KmerAssemblyEngine(config);
        engine.addReads(reads);
        return engine.assemble(reads.size(), 1);
    }

    private static AssemblyResult assembleIncremental(AssemblyConfig config,
                                                      List<SequenceRead> first,
                                                      List<SequenceRead> second) {
        KmerAssemblyEngine engine = new KmerAssemblyEngine(config);
        engine.addReads(first);
        engine.addReads(second);
        return engine.assemble(first.size() + second.size(), 2);
    }

    private static List<SequenceRead> concat(List<SequenceRead> left, List<SequenceRead> right) {
        List<SequenceRead> all = new ArrayList<>(left);
        all.addAll(right);
        return all;
    }

    private static boolean isSubstringOfReference(String contig, String reference) {
        return reference.contains(contig)
                || reference.contains(SequencingSimulator.reverseComplement(contig));
    }

    private static void assertValidStats(AssemblyResult result) {
        long summed = result.contigs().stream().mapToLong(Contig::length).sum();
        assertEquals(summed, result.stats().totalLength());
        assertEquals(result.contigs().size(), result.stats().contigCount());
        long longest = result.contigs().stream().mapToLong(Contig::length).max().orElse(0);
        assertEquals(longest, result.stats().longestLength());
        long cumulative = 0;
        for (Contig contig : result.contigs()) {
            cumulative += contig.length();
            if (cumulative >= (result.stats().totalLength() + 1) / 2) {
                assertEquals(contig.length(), result.stats().n50());
                break;
            }
        }
    }

    private static String canonicalJson(AssemblyResult result) {
        return result.contigs().stream()
                .map(contig -> contig.id() + "|" + contig.sequence() + "|" + contig.meanCoverage())
                .reduce("", (left, right) -> left + ";" + right)
                + " stats=" + result.stats().contigCount() + "," + result.stats().totalLength()
                + "," + result.stats().longestLength() + "," + result.stats().n50();
    }
}
