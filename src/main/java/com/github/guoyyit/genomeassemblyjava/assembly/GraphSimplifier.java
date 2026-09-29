package com.github.guoyyit.genomeassemblyjava.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Iteratively simplifies the graph of solid k-mers: clips short low-coverage tips and
 * merges simple bubbles, without disconnecting any true path.
 */
final class GraphSimplifier {

    private GraphSimplifier() {
    }

    /**
     * Simplifies the given solid k-mer set in place and returns the final unitigs.
     * Only dead-end branches and losing bubble sides are ever removed, so every true
     * path keeps its connectivity; the input map stays usable for later incremental
     * updates afterwards.
     */
    static List<Unitig> simplify(Map<Long, Integer> solidKmers, AssemblyParams params) {
        int k = params.k();
        while (true) {
            List<Unitig> unitigs = DeBruijnGraph.extractUnitigs(solidKmers, k);
            Set<Long> doomed = findTips(unitigs, solidKmers, params);
            if (doomed.isEmpty()) {
                doomed = findBubbleLosers(unitigs, solidKmers, params);
            }
            if (doomed.isEmpty()) {
                return unitigs;
            }
            solidKmers.keySet().removeAll(doomed);
        }
    }

    /**
     * Finds k-mers of tip unitigs: maximal paths with exactly one dead end, at most
     * tipLength k-mers, and mean coverage at most tipCoverageRatio of the coverage of
     * the unitigs attached at their junction side.
     */
    private static Set<Long> findTips(List<Unitig> unitigs, Map<Long, Integer> kmers, AssemblyParams params) {
        int k = params.k();
        if (params.tipLength() <= 0) {
            return Set.of();
        }
        Map<Long, List<Double>> endpointCoverages = endpointCoverages(unitigs, k);
        Set<Long> doomed = new HashSet<>();
        for (Unitig unitig : unitigs) {
            if (unitig.kmerCount() > params.tipLength()) {
                continue;
            }
            long first = KmerCounter.canonical(KmerCounter.encode(unitig.sequence().substring(0, k)), k);
            long last = KmerCounter.canonical(
                    KmerCounter.encode(unitig.sequence().substring(unitig.sequence().length() - k)), k);
            boolean leftDead = DeBruijnGraph.leftDegree(first, kmers, k) == 0;
            boolean rightDead = DeBruijnGraph.rightDegree(last, kmers, k) == 0;
            if (leftDead == rightDead) {
                continue;
            }
            double junctionCoverage = 0.0;
            for (int b = 0; b < 4; b++) {
                long junction = leftDead
                        ? DeBruijnGraph.rightNeighbor(last, k, b)
                        : DeBruijnGraph.leftNeighbor(first, k, b);
                for (double coverage : endpointCoverages.getOrDefault(junction, List.of())) {
                    junctionCoverage = Math.max(junctionCoverage, coverage);
                }
            }
            if (junctionCoverage > 0.0
                    && unitig.meanCoverage() <= params.tipCoverageRatio() * junctionCoverage) {
                doomed.addAll(kmersOf(unitig, k));
            }
        }
        return doomed;
    }

    /**
     * Finds k-mers of losing sides of simple bubbles: groups of unitigs that share the
     * same pair of junction endpoints, where the weaker sides have at most
     * bubbleCoverageRatio of the strongest side's coverage.
     */
    private static Set<Long> findBubbleLosers(
            List<Unitig> unitigs, Map<Long, Integer> kmers, AssemblyParams params) {
        int k = params.k();
        if (params.maxBubbleLength() <= 0) {
            return Set.of();
        }
        Map<String, List<Unitig>> byJunctions = new TreeMap<>();
        for (Unitig unitig : unitigs) {
            if (unitig.kmerCount() > params.maxBubbleLength()) {
                continue;
            }
            String signature = junctionSignature(unitig, kmers, k);
            if (signature != null) {
                byJunctions.computeIfAbsent(signature, key -> new ArrayList<>()).add(unitig);
            }
        }
        Set<Long> doomed = new HashSet<>();
        for (List<Unitig> group : byJunctions.values()) {
            if (group.size() < 2) {
                continue;
            }
            double strongest = 0.0;
            for (Unitig unitig : group) {
                strongest = Math.max(strongest, unitig.meanCoverage());
            }
            for (Unitig unitig : group) {
                if (unitig.meanCoverage() < strongest
                        && unitig.meanCoverage() <= params.bubbleCoverageRatio() * strongest) {
                    doomed.addAll(kmersOf(unitig, k));
                }
            }
        }
        return doomed;
    }

    /**
     * Signature of a unitig's junction endpoints: the sorted encoded k-mers adjacent to
     * its left and right ends, or null when the unitig does not sit between exactly one
     * junction on each side.
     */
    private static String junctionSignature(Unitig unitig, Map<Long, Integer> kmers, int k) {
        long first = KmerCounter.canonical(KmerCounter.encode(unitig.sequence().substring(0, k)), k);
        long last = KmerCounter.canonical(
                KmerCounter.encode(unitig.sequence().substring(unitig.sequence().length() - k)), k);
        List<Long> leftJunctions = new ArrayList<>();
        List<Long> rightJunctions = new ArrayList<>();
        for (int b = 0; b < 4; b++) {
            long left = DeBruijnGraph.leftNeighbor(first, k, b);
            if (kmers.containsKey(left)) {
                leftJunctions.add(left);
            }
            long right = DeBruijnGraph.rightNeighbor(last, k, b);
            if (kmers.containsKey(right)) {
                rightJunctions.add(right);
            }
        }
        if (leftJunctions.size() != 1 || rightJunctions.size() != 1) {
            return null;
        }
        return leftJunctions.get(0) + "|" + rightJunctions.get(0);
    }

    /**
     * Maps every unitig endpoint k-mer to the mean coverages of the unitigs touching it.
     */
    private static Map<Long, List<Double>> endpointCoverages(List<Unitig> unitigs, int k) {
        Map<Long, List<Double>> index = new HashMap<>();
        for (Unitig unitig : unitigs) {
            long first = KmerCounter.canonical(KmerCounter.encode(unitig.sequence().substring(0, k)), k);
            long last = KmerCounter.canonical(
                    KmerCounter.encode(unitig.sequence().substring(unitig.sequence().length() - k)), k);
            index.computeIfAbsent(first, key -> new ArrayList<>()).add(unitig.meanCoverage());
            index.computeIfAbsent(last, key -> new ArrayList<>()).add(unitig.meanCoverage());
        }
        return index;
    }

    private static List<Long> kmersOf(Unitig unitig, int k) {
        List<Long> result = new ArrayList<>();
        String sequence = unitig.sequence();
        for (int i = 0; i + k <= sequence.length(); i++) {
            result.add(KmerCounter.canonical(KmerCounter.encode(sequence.substring(i, i + k)), k));
        }
        return result;
    }
}
