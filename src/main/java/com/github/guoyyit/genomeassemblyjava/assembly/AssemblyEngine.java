package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mutable per-task assembly state: k-mer counts plus applied-batch bookkeeping.
 * Not thread-safe; guarded by the owning task's lock. The assembly result is a pure
 * function of the accumulated k-mer counts, so incremental updates revise previous
 * conclusions and always converge to the same result as a one-shot full assembly.
 */
public final class AssemblyEngine {

    private final AssemblyParams params;
    private final KmerCounter counter;
    private final Set<String> appliedBatchIds = new LinkedHashSet<>();
    private long readsApplied;

    public AssemblyEngine(AssemblyParams params) {
        this.params = params;
        this.counter = new KmerCounter(params.k(), params.minBaseQuality());
    }

    public AssemblyParams params() {
        return params;
    }

    /**
     * Applies one batch. Returns false without changing state when the batchId was
     * already applied, which makes submissions idempotent.
     */
    public boolean applyBatch(String batchId, List<Read> reads) {
        if (!appliedBatchIds.add(batchId)) {
            return false;
        }
        counter.addAll(reads);
        readsApplied += reads.size();
        return true;
    }

    public long readsApplied() {
        return readsApplied;
    }

    public int batchesApplied() {
        return appliedBatchIds.size();
    }

    public long distinctKmers() {
        return counter.distinctKmers();
    }

    public AssemblyResult assemble() {
        Map<Long, Integer> solid = new HashMap<>();
        for (Map.Entry<Long, Integer> entry : counter.snapshotCounts().entrySet()) {
            if (entry.getValue() >= params.minKmerCount()) {
                solid.put(entry.getKey(), entry.getValue());
            }
        }
        List<Unitig> unitigs = GraphSimplifier.simplify(solid, params);
        List<Contig> contigs = new ArrayList<>();
        for (Unitig unitig : unitigs) {
            if (unitig.sequence().length() >= params.minContigLength()) {
                contigs.add(new Contig(null, unitig.sequence(), unitig.meanCoverage()));
            }
        }
        contigs.sort(Comparator.comparingInt((Contig c) -> c.sequence().length())
                .reversed()
                .thenComparing(Contig::sequence));
        List<Contig> named = new ArrayList<>(contigs.size());
        for (int i = 0; i < contigs.size(); i++) {
            Contig contig = contigs.get(i);
            named.add(new Contig("contig_" + (i + 1), contig.sequence(), contig.meanCoverage()));
        }
        return new AssemblyResult(named, AssemblyStats.of(named));
    }

    public Map<Long, Integer> exportCounts() {
        return counter.snapshotCounts();
    }

    public List<String> appliedBatchIds() {
        return List.copyOf(appliedBatchIds);
    }

    /**
     * Restores previously exported state into a fresh engine. Used by crash recovery.
     */
    public void restore(long readsApplied, Collection<String> batchIds, Map<Long, Integer> counts) {
        this.readsApplied = readsApplied;
        this.appliedBatchIds.addAll(batchIds);
        this.counter.restoreCounts(counts);
    }
}
