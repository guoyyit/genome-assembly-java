package com.github.guoyyit.genomeassemblyjava.engine;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyStats;
import com.github.guoyyit.genomeassemblyjava.model.Contig;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 增量式 de Bruijn 图组装引擎：只维护可增量合并的规范 k-mer/(k+1)-mer 覆盖度计数，
 * 图构建、纠错、简化与 contig 提取均为派生计算。
 *
 * <p>计数采用规范 mer（正链编码与反向互补编码的较小者），因此读段方向与提交顺序
 * 不影响计数结果；所有派生步骤都只依赖计数、按确定顺序遍历，相同输入必定逐字节等价。</p>
 */
public class KmerAssemblyEngine {

    private final int k;
    private final AssemblyConfig config;
    private final LongIntHashMap kmerCounts = new LongIntHashMap();
    private final LongIntHashMap edgeCounts = new LongIntHashMap();

    public KmerAssemblyEngine(AssemblyConfig config) {
        this.config = config;
        this.k = config.kmerSize();
    }

    /**
     * 加入一条读段：先按质量阈值修剪末端；随后以质量串作为门控，
     * 仅统计完全由高质量碱基构成的 k-mer 与 (k+1)-mer。
     */
    public void addRead(SequenceRead read) {
        String qualities = read.qualities();
        String bases = read.bases().toUpperCase();
        int first = 0;
        int last = bases.length();
        if (config.qualityTrimThreshold() > 0 && qualities != null && !qualities.isEmpty()) {
            while (first < last && qualityOf(qualities.charAt(first)) < config.qualityTrimThreshold()) {
                first++;
            }
            while (last > first
                    && qualityOf(qualities.charAt(last - 1)) < config.qualityTrimThreshold()) {
                last--;
            }
        }
        if (last - first < k) {
            return;
        }
        boolean qualityGated = config.qualityTrimThreshold() > 0
                && qualities != null && !qualities.isEmpty();
        for (int i = first; i + k <= last; i++) {
            if (qualityGated && hasLowQuality(qualities, i, i + k)) {
                continue;
            }
            long code = DnaEncoding.encode(bases.substring(i, i + k));
            kmerCounts.addAndGet(canonical(code, k) + 1L, 1);
        }
        int edgeLength = k + 1;
        for (int i = first; i + edgeLength <= last; i++) {
            if (qualityGated && hasLowQuality(qualities, i, i + edgeLength)) {
                continue;
            }
            long code = DnaEncoding.encode(bases.substring(i, i + edgeLength));
            edgeCounts.addAndGet(canonical(code, edgeLength) + 1L, 1);
        }
    }

    public void addReads(Collection<SequenceRead> reads) {
        for (SequenceRead read : reads) {
            addRead(read);
        }
    }

    /**
     * 一次性组装全部读段的便捷入口：语义等价于新引擎加入全部读段后调用 assemble。
     */
    public static AssemblyResult assembleOnce(AssemblyConfig config,
                                              Collection<SequenceRead> reads,
                                              long readCount,
                                              long batchCount) {
        KmerAssemblyEngine engine = new KmerAssemblyEngine(config);
        engine.addReads(reads);
        return engine.assemble(readCount, batchCount);
    }

    public AssemblyResult assemble(long readCount, long batchCount) {
        AssemblyGraph graph = buildGraph();
        simplify(graph);
        List<RawContig> rawContigs = extractContigs(graph);
        List<Contig> contigs = deduplicateAndOrder(rawContigs);
        return new AssemblyResult(config, readCount, batchCount, contigs, computeStats(contigs));
    }

    private boolean hasLowQuality(String qualities, int from, int to) {
        for (int i = from; i < to; i++) {
            if (qualityOf(qualities.charAt(i)) < config.qualityTrimThreshold()) {
                return true;
            }
        }
        return false;
    }

    private static int qualityOf(char encoded) {
        return encoded - 33;
    }

    private long canonical(long code, int length) {
        long rc = DnaEncoding.reverseComplement(code, length);
        return Math.min(code, rc);
    }

    private AssemblyGraph buildGraph() {
        TreeSet<Long> trustedNodes = new TreeSet<>();
        kmerCounts.forEachSorted((storedKey, count) -> {
            if (count >= config.minCoverage()) {
                trustedNodes.add(storedKey - 1L);
            }
        });
        AssemblyGraph graph = new AssemblyGraph();
        int edgeLength = k + 1;
        edgeCounts.forEachSorted((storedKey, count) -> {
            if (count < config.minCoverage()) {
                return;
            }
            long canonicalEdge = storedKey - 1L;
            addCanonicalEdge(graph, canonicalEdge, count, trustedNodes);
            long rcEdge = DnaEncoding.reverseComplement(canonicalEdge, edgeLength);
            if (rcEdge != canonicalEdge) {
                addCanonicalEdge(graph, rcEdge, count, trustedNodes);
            }
        });
        return graph;
    }

    private void addCanonicalEdge(AssemblyGraph graph, long canonicalEdge, int weight,
                                  TreeSet<Long> trustedNodes) {
        int nodeBits = 2 * k;
        long nodeMask = (1L << nodeBits) - 1L;
        long prefix = canonicalEdge >>> 2;
        long suffix = canonicalEdge & nodeMask;
        if (trustedNodes.contains(canonical(prefix, k))
                && trustedNodes.contains(canonical(suffix, k))) {
            graph.addEdge(prefix, suffix, weight);
        }
    }

    private void simplify(AssemblyGraph graph) {
        boolean changed = true;
        int guard = graph.edgeCount() + 2;
        while (changed && guard-- > 0) {
            changed = clipTips(graph);
            changed |= popBubbles(graph);
        }
    }

    private boolean clipTips(AssemblyGraph graph) {
        boolean removedAny = false;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Long start : new ArrayList<>(graph.nodesWithNoIncoming())) {
                List<long[]> path = linearPathFrom(start, graph, config.maxTipLength());
                if (path != null) {
                    graph.removePath(path);
                    changed = true;
                    removedAny = true;
                }
            }
            for (Long end : new ArrayList<>(graph.nodesWithNoOutgoing())) {
                List<long[]> path = linearPathTo(end, graph, config.maxTipLength());
                if (path != null) {
                    graph.removePath(path);
                    changed = true;
                    removedAny = true;
                }
            }
        }
        return removedAny;
    }

    private List<long[]> linearPathFrom(Long start, AssemblyGraph graph, int maxEdges) {
        if (maxEdges <= 0 || graph.outDegree(start) != 1 || graph.inDegree(start) != 0) {
            return null;
        }
        List<long[]> path = new ArrayList<>();
        long current = start;
        while (path.size() < maxEdges) {
            long next = graph.soleOut(current);
            path.add(new long[]{current, next});
            if (graph.inDegree(next) != 1 || graph.outDegree(next) != 1) {
                return path;
            }
            current = next;
        }
        return null;
    }

    private List<long[]> linearPathTo(Long end, AssemblyGraph graph, int maxEdges) {
        if (maxEdges <= 0 || graph.inDegree(end) != 1 || graph.outDegree(end) != 0) {
            return null;
        }
        List<long[]> path = new ArrayList<>();
        long current = end;
        while (path.size() < maxEdges) {
            long previous = graph.soleIn(current);
            path.add(0, new long[]{previous, current});
            if (graph.inDegree(previous) != 1 || graph.outDegree(previous) != 1) {
                return path;
            }
            current = previous;
        }
        return null;
    }

    private boolean popBubbles(AssemblyGraph graph) {
        boolean removedAny = false;
        int bubbleBound = 2 * k + Math.max(config.maxTipLength(), k);
        for (Long branch : new ArrayList<>(graph.branchNodes())) {
            if (graph.outDegree(branch) < 2) {
                continue;
            }
            TreeMap<Long, List<long[]>> converging = new TreeMap<>();
            TreeMap<Long, Double> pathWeights = new TreeMap<>();
            for (Long firstNext : graph.out(branch)) {
                List<long[]> path = extendBranch(branch, firstNext, graph, bubbleBound);
                if (path == null) {
                    continue;
                }
                long endNode = path.get(path.size() - 1)[1];
                if (converging.containsKey(endNode)) {
                    continue;
                }
                converging.put(endNode, path);
                pathWeights.put(endNode, meanWeight(path, graph));
            }
            if (converging.size() < 2) {
                continue;
            }
            List<Map.Entry<Long, List<long[]>>> paths = new ArrayList<>(converging.entrySet());
            paths.sort(Comparator
                    .<Map.Entry<Long, List<long[]>>, Double>comparing(
                            entry -> pathWeights.get(entry.getKey()))
                    .reversed()
                    .thenComparing(entry -> pathSequence(entry.getValue())));
            for (int i = 1; i < paths.size(); i++) {
                graph.removePath(paths.get(i).getValue());
                removedAny = true;
            }
        }
        return removedAny;
    }

    private List<long[]> extendBranch(long branch, long firstNext, AssemblyGraph graph, int bound) {
        if (graph.inDegree(firstNext) != 1) {
            return null;
        }
        List<long[]> path = new ArrayList<>();
        path.add(new long[]{branch, firstNext});
        long current = firstNext;
        while (path.size() < bound) {
            if (graph.inDegree(current) != 1 || graph.outDegree(current) != 1) {
                return path;
            }
            long next = graph.soleOut(current);
            path.add(new long[]{current, next});
            current = next;
        }
        return null;
    }

    private double meanWeight(List<long[]> path, AssemblyGraph graph) {
        long sum = 0;
        for (long[] edge : path) {
            sum += graph.weight(edge[0], edge[1]);
        }
        return (double) sum / path.size();
    }

    private String pathSequence(List<long[]> path) {
        long first = path.get(0)[0];
        StringBuilder builder = new StringBuilder(DnaEncoding.decode(first, k));
        for (long[] edge : path) {
            builder.append(DnaEncoding.decodeBase((int) (edge[1] & 3L)));
        }
        return builder.toString();
    }

    private List<RawContig> extractContigs(AssemblyGraph graph) {
        List<RawContig> rawContigs = new ArrayList<>();
        TreeSet<Long> visited = new TreeSet<>();
        for (Long node : graph.nodes()) {
            if (graph.outDegree(node) == 0
                    || (graph.inDegree(node) == 1 && graph.outDegree(node) == 1)) {
                continue;
            }
            for (Long next : graph.out(node)) {
                if (!visited.contains(edgeKey(node, next))) {
                    rawContigs.add(walkContig(node, next, graph, visited));
                }
            }
        }
        for (Long node : graph.nodes()) {
            for (Long next : graph.out(node)) {
                if (!visited.contains(edgeKey(node, next))) {
                    rawContigs.add(walkContig(node, next, graph, visited));
                }
            }
        }
        return rawContigs;
    }

    private RawContig walkContig(long start, long firstNext, AssemblyGraph graph,
                                 TreeSet<Long> visited) {
        StringBuilder sequence = new StringBuilder(DnaEncoding.decode(start, k));
        long weightSum = graph.weight(start, firstNext);
        int edgeTotal = 1;
        markVisited(visited, start, firstNext);
        sequence.append(DnaEncoding.decodeBase((int) (firstNext & 3L)));
        long current = firstNext;
        while (graph.inDegree(current) == 1 && graph.outDegree(current) == 1) {
            long next = graph.soleOut(current);
            if (visited.contains(edgeKey(current, next))) {
                break;
            }
            markVisited(visited, current, next);
            sequence.append(DnaEncoding.decodeBase((int) (next & 3L)));
            weightSum += graph.weight(current, next);
            edgeTotal++;
            current = next;
        }
        double meanCoverage = Math.round((double) weightSum / edgeTotal * 100.0) / 100.0;
        return new RawContig(sequence.toString(), meanCoverage);
    }

    private void markVisited(TreeSet<Long> visited, long from, long to) {
        visited.add(edgeKey(from, to));
        visited.add(edgeKey(reverseNode(to), reverseNode(from)));
    }

    private long edgeKey(long from, long to) {
        return ((from + 1L) << 32) | (to + 1L);
    }

    private long reverseNode(long orientedNode) {
        return DnaEncoding.reverseComplement(orientedNode, k);
    }

    private List<Contig> deduplicateAndOrder(List<RawContig> rawContigs) {
        TreeMap<String, RawContig> canonical = new TreeMap<>();
        for (RawContig raw : rawContigs) {
            String rc = DnaEncoding.reverseComplement(raw.sequence());
            String key = raw.sequence().compareTo(rc) <= 0 ? raw.sequence() : rc;
            canonical.putIfAbsent(key, raw);
        }
        List<String> keys = new ArrayList<>(canonical.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed()
                .thenComparing(String::compareTo));
        List<Contig> contigs = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String sequence = keys.get(i);
            double meanCoverage = canonical.get(sequence).meanCoverage();
            contigs.add(new Contig(String.format("contig_%06d", i + 1),
                    sequence, sequence.length(), meanCoverage));
        }
        return contigs;
    }

    private AssemblyStats computeStats(List<Contig> contigs) {
        long totalLength = 0;
        long longest = 0;
        List<Long> lengths = new ArrayList<>();
        for (Contig contig : contigs) {
            lengths.add(contig.length());
            totalLength += contig.length();
            longest = Math.max(longest, contig.length());
        }
        lengths.sort(Comparator.reverseOrder());
        long half = (totalLength + 1) / 2;
        long accumulated = 0;
        long n50 = 0;
        for (Long length : lengths) {
            accumulated += length;
            if (accumulated >= half) {
                n50 = length;
                break;
            }
        }
        return new AssemblyStats(contigs.size(), totalLength, longest, n50);
    }

    private record RawContig(String sequence, double meanCoverage) {
    }

    /**
     * 面向 k-mer 空间的有向图：删除任意有向边时同步删除其反向互补镜像边，
     * 始终保持双链对称。全部邻接表使用 TreeMap，遍历顺序确定。
     */
    private final class AssemblyGraph {

        private final TreeMap<Long, TreeMap<Long, Integer>> outgoing = new TreeMap<>();
        private final TreeMap<Long, TreeSet<Long>> incoming = new TreeMap<>();

        void addEdge(long from, long to, int weight) {
            outgoing.computeIfAbsent(from, ignored -> new TreeMap<>())
                    .merge(to, weight, Math::min);
            incoming.computeIfAbsent(to, ignored -> new TreeSet<>()).add(from);
            outgoing.computeIfAbsent(to, ignored -> new TreeMap<>());
            incoming.computeIfAbsent(from, ignored -> new TreeSet<>());
        }

        void removePath(List<long[]> path) {
            for (long[] edge : path) {
                removeEdge(edge[0], edge[1]);
            }
        }

        void removeEdge(long from, long to) {
            TreeMap<Long, Integer> outs = outgoing.get(from);
            if (outs == null || !outs.containsKey(to)) {
                return;
            }
            outs.remove(to);
            TreeSet<Long> ins = incoming.get(to);
            if (ins != null) {
                ins.remove(from);
            }
            long mirrorFrom = reverseNode(to);
            long mirrorTo = reverseNode(from);
            TreeMap<Long, Integer> mirrorOuts = outgoing.get(mirrorFrom);
            if (mirrorOuts != null) {
                mirrorOuts.remove(mirrorTo);
            }
            TreeSet<Long> mirrorIns = incoming.get(mirrorTo);
            if (mirrorIns != null) {
                mirrorIns.remove(mirrorFrom);
            }
        }

        Iterable<Long> nodes() {
            return outgoing.keySet();
        }

        List<Long> nodesWithNoIncoming() {
            List<Long> result = new ArrayList<>();
            for (Long node : outgoing.keySet()) {
                TreeSet<Long> ins = incoming.get(node);
                if (ins == null || ins.isEmpty()) {
                    result.add(node);
                }
            }
            return result;
        }

        List<Long> nodesWithNoOutgoing() {
            List<Long> result = new ArrayList<>();
            for (Map.Entry<Long, TreeMap<Long, Integer>> entry : outgoing.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }

        List<Long> branchNodes() {
            List<Long> result = new ArrayList<>();
            for (Long node : outgoing.keySet()) {
                if (outDegree(node) >= 2) {
                    result.add(node);
                }
            }
            return result;
        }

        int outDegree(long node) {
            TreeMap<Long, Integer> outs = outgoing.get(node);
            return outs == null ? 0 : outs.size();
        }

        int inDegree(long node) {
            TreeSet<Long> ins = incoming.get(node);
            return ins == null ? 0 : ins.size();
        }

        Iterable<Long> out(long node) {
            return outgoing.getOrDefault(node, new TreeMap<>()).keySet();
        }

        long soleOut(long node) {
            return outgoing.get(node).firstKey();
        }

        long soleIn(long node) {
            return incoming.get(node).first();
        }

        int weight(long from, long to) {
            return outgoing.get(from).get(to);
        }

        int edgeCount() {
            int count = 0;
            for (TreeMap<Long, Integer> outs : outgoing.values()) {
                count += outs.size();
            }
            return count;
        }
    }
}
