package com.github.guoyyit.genomeassemblyjava.engine;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyStats;
import com.github.guoyyit.genomeassemblyjava.model.Contig;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 增量式 de Bruijn 图组装引擎。
 *
 * <p>状态只有一样东西：canonical k-mer 的覆盖度计数（{@code long[]} 可变计数）。
 * 批次受理时只做计数累加，图构建、悬挂末端移除、contig 提取与统计指标全部是派生计算，
 * 因此重复提交/重启重放只要计数一致，组装结果就逐字节一致。
 *
 * <p>质量值实际参与错误识别：先按 {@link AssemblyConfig#qualityTrimThreshold()} 修剪末端
 * 低质量碱基；随后每个 k-mer 窗口只有当其最低 Phred 质量达到阈值（默认 Q20）时才计数，
 * 携带低质量错误碱基的 k-mer 不会进入图，低频错误路径因此被剔除。
 */
public class KmerAssemblyEngine {

    /** 未配置修剪阈值时默认的 k-mer 质量下限（Phred+33，Q20）。 */
    static final int DEFAULT_QUALITY_FLOOR = 20;

    private static final int PHRED_OFFSET = 33;

    private final AssemblyConfig config;
    private final int k;
    private final long kmerMask;
    private final int qualityFloor;

    /** canonical k-mer -> 覆盖度计数（用数组避免装箱）。 */
    private final Map<Long, long[]> coverage = new HashMap<>();

    public KmerAssemblyEngine(AssemblyConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        config.validate();
        this.k = config.kmerSize();
        this.kmerMask = (1L << (2 * k)) - 1;
        this.qualityFloor = config.qualityTrimThreshold() > 0
                ? config.qualityTrimThreshold() : DEFAULT_QUALITY_FLOOR;
    }

    public AssemblyConfig getConfig() {
        return config;
    }

    /** 受理一条读段：修剪 -> 质量过滤 -> k-mer 计数。 */
    public synchronized void addRead(SequenceRead read) {
        Objects.requireNonNull(read, "read");
        String bases = read.bases();
        if (bases == null || bases.isEmpty()) {
            return;
        }
        int[] range = trimRange(bases, read.qualities());
        int start = range[0];
        int end = range[1];
        if (end - start < config.minReadLength()) {
            return;
        }
        for (int i = start; i + k <= end; i++) {
            if (minQuality(read.qualities(), i, i + k) < qualityFloor) {
                continue;
            }
            String window = bases.substring(i, i + k);
            if (hasAmbiguous(window)) {
                continue;
            }
            long canonical = canonical(DnaEncoding.encode(window));
            coverage.computeIfAbsent(canonical, key -> new long[1])[0]++;
        }
    }

    public synchronized void addReads(Collection<SequenceRead> reads) {
        if (reads == null) {
            return;
        }
        for (SequenceRead read : reads) {
            addRead(read);
        }
    }

    /** 基于当前计数派生组装结果；纯函数式派生，不修改任何状态。 */
    public synchronized AssemblyResult assemble(long readCount, long batchCount) {
        Set<Long> solid = buildSolidKmers();
        Set<Long> removed = removeTips(solid);
        List<Contig> contigs = extractContigs(solid, removed);
        AssemblyStats stats = computeStats(contigs);
        return new AssemblyResult(config, readCount, batchCount, List.copyOf(contigs), stats);
    }

    // ------------------------------------------------------------------
    // k-mer 计数
    // ------------------------------------------------------------------

    private long canonical(long encoded) {
        long rc = DnaEncoding.reverseComplement(encoded, k);
        return Math.min(encoded, rc);
    }

    private static boolean hasAmbiguous(String window) {
        for (int i = 0; i < window.length(); i++) {
            if (DnaEncoding.isAmbiguous(window.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** 修剪末端低质量碱基，返回保留区间 [start, end)（原始坐标）。 */
    private int[] trimRange(String bases, String qualities) {
        int start = 0;
        int end = bases.length();
        int threshold = config.qualityTrimThreshold();
        if (threshold > 0 && qualities != null) {
            while (start < end && baseQuality(qualities, start) < threshold) {
                start++;
            }
            while (end > start && baseQuality(qualities, end - 1) < threshold) {
                end--;
            }
        }
        return new int[]{start, end};
    }

    private static int baseQuality(String qualities, int position) {
        if (qualities == null || position < 0 || position >= qualities.length()) {
            return 40;
        }
        return Math.max(0, qualities.charAt(position) - PHRED_OFFSET);
    }

    private static int minQuality(String qualities, int fromInclusive, int toExclusive) {
        int min = Integer.MAX_VALUE;
        for (int i = fromInclusive; i < toExclusive; i++) {
            min = Math.min(min, baseQuality(qualities, i));
        }
        return min;
    }

    // ------------------------------------------------------------------
    // 图构建与简化
    // ------------------------------------------------------------------

    private Set<Long> buildSolidKmers() {
        Set<Long> solid = new LinkedHashSet<>();
        for (Map.Entry<Long, long[]> entry : coverage.entrySet()) {
            if (entry.getValue()[0] >= config.minCoverage()) {
                solid.add(entry.getKey());
            }
        }
        return solid;
    }

    /** 节点在 solid 集合中的无向邻居（前向/后向延伸的并集，去自环）。 */
    private Set<Long> neighbors(long node, Set<Long> solid, Set<Long> removed) {
        Set<Long> result = new HashSet<>();
        for (int base = 0; base < 4; base++) {
            long forward = ((node << 2) | base) & kmerMask;
            long fwdCanon = canonical(forward);
            if (solid.contains(fwdCanon) && !removed.contains(fwdCanon) && fwdCanon != node) {
                result.add(fwdCanon);
            }
            long backward = (node >> 2) | ((long) base << (2 * (k - 1)));
            long bwdCanon = canonical(backward);
            if (solid.contains(bwdCanon) && !removed.contains(bwdCanon) && bwdCanon != node) {
                result.add(bwdCanon);
            }
        }
        return result;
    }

    /**
     * 迭代移除悬挂末端分支（tip）：从死端（度数 1）出发，沿度数为 2 的链行走，
     * 若在 maxTipLength 条边内到达分支节点（度数 >2），则这条"分支→死端"的短路径
     * 按测序错误移除。
     *
     * <p>死端到死端的孤立片段是真实连续序列（仅因覆盖度缺口断裂），一律保留；
     * 超过长度上限的路径也保留（可能是真实序列），从而保证真实序列的连通性不受损。
     */
    private Set<Long> removeTips(Set<Long> solid) {
        Set<Long> removed = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (long node : solid) {
                if (removed.contains(node)) {
                    continue;
                }
                Set<Long> direct = neighbors(node, solid, removed);
                if (direct.size() != 1) {
                    continue;
                }
                List<Long> path = new ArrayList<>();
                path.add(node);
                Long cursor = direct.iterator().next();
                long prev = node;
                boolean reachedBranch = false;
                int edges = 0;
                while (cursor != null && edges < config.maxTipLength()) {
                    path.add(cursor);
                    edges++;
                    Set<Long> nextNeighbors = neighbors(cursor, solid, removed);
                    nextNeighbors.remove(prev);
                    if (nextNeighbors.size() > 1) {
                        reachedBranch = true;
                        break;
                    }
                    if (nextNeighbors.isEmpty()) {
                        // 到达另一个死端：这是真实片段，不是悬挂分支，保留。
                        break;
                    }
                    prev = cursor;
                    cursor = nextNeighbors.iterator().next();
                }
                if (reachedBranch) {
                    removed.addAll(path);
                    changed = true;
                }
            }
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // contig 提取
    // ------------------------------------------------------------------

    private List<Contig> extractContigs(Set<Long> solid, Set<Long> removed) {
        Set<Long> alive = new LinkedHashSet<>(solid);
        alive.removeAll(removed);

        Set<Long> endpoints = new LinkedHashSet<>();
        for (long node : alive) {
            if (neighbors(node, solid, removed).size() != 2) {
                endpoints.add(node);
            }
        }

        Set<Long> usedEdges = new HashSet<>();
        Set<Long> usedSingletons = new HashSet<>();
        List<Contig> contigs = new ArrayList<>();

        for (long start : endpoints) {
            Set<Long> startNeighbors = neighbors(start, solid, removed);
            if (startNeighbors.isEmpty()) {
                // 孤立 k-mer（如长度恰好为 k 的读段）→ 长度为 k 的 contig
                if (usedSingletons.add(start)) {
                    contigs.add(buildSingletonContig(contigs.size(), start));
                }
                continue;
            }
            for (long next : startNeighbors) {
                if (!usedEdges.add(edgeKey(start, next))) {
                    continue;
                }
                List<Long> path = walkPath(start, next, solid, removed, endpoints, usedEdges);
                contigs.add(buildContig(contigs.size(), path));
            }
        }
        // 既无端点也未被覆盖的孤立环（随机数据中罕见）。
        for (long node : alive) {
            if (endpoints.contains(node)) {
                continue;
            }
            boolean covered = false;
            for (long other : alive) {
                if (other != node && usedEdges.contains(edgeKey(node, other))) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                List<Long> path = walkCycle(node, solid, removed, usedEdges);
                contigs.add(buildContig(contigs.size(), path));
            }
        }

        contigs.sort(Comparator.comparingLong(Contig::length).reversed()
                .thenComparing(Contig::sequence));
        List<Contig> reindexed = new ArrayList<>();
        for (int i = 0; i < contigs.size(); i++) {
            Contig c = contigs.get(i);
            reindexed.add(new Contig("contig-" + (i + 1), c.sequence(), c.length(), c.meanCoverage()));
        }
        return reindexed;
    }

    /** 孤立 k-mer 的 contig：序列即该 k-mer 的正向解码。 */
    private Contig buildSingletonContig(int index, long node) {
        String sequence = DnaEncoding.decode(node, k);
        double meanCoverage = coverage.getOrDefault(node, new long[1])[0];
        return new Contig("contig-" + (index + 1), sequence, sequence.length(), meanCoverage);
    }

    private List<Long> walkPath(long start, long first, Set<Long> solid, Set<Long> removed,
                                Set<Long> endpoints, Set<Long> usedEdges) {
        List<Long> path = new ArrayList<>();
        path.add(start);
        path.add(first);
        long prev = start;
        long cursor = first;
        while (!endpoints.contains(cursor)) {
            Set<Long> nextNeighbors = neighbors(cursor, solid, removed);
            nextNeighbors.remove(prev);
            if (nextNeighbors.size() != 1) {
                break;
            }
            long next = nextNeighbors.iterator().next();
            usedEdges.add(edgeKey(cursor, next));
            prev = cursor;
            cursor = next;
            path.add(cursor);
        }
        return path;
    }

    private List<Long> walkCycle(long seed, Set<Long> solid, Set<Long> removed, Set<Long> usedEdges) {
        List<Long> path = new ArrayList<>();
        path.add(seed);
        long prev = -1;
        long cursor = seed;
        while (true) {
            Set<Long> nextNeighbors = neighbors(cursor, solid, removed);
            if (prev >= 0) {
                nextNeighbors.remove(prev);
            }
            if (nextNeighbors.isEmpty()) {
                break;
            }
            long next = nextNeighbors.iterator().next();
            if (next == seed) {
                usedEdges.add(edgeKey(cursor, next));
                path.add(next);
                break;
            }
            if (!usedEdges.add(edgeKey(cursor, next))) {
                break;
            }
            prev = cursor;
            cursor = next;
            path.add(cursor);
        }
        return path;
    }

    /** 沿 canonical 路径还原序列：起点任选方向，必要时翻转反向互补。 */
    private Contig buildContig(int index, List<Long> path) {
        long start = path.get(0);
        long second = path.get(1);
        long oriented = start;
        Long firstBase = findAppend(oriented, second);
        if (firstBase == null) {
            oriented = DnaEncoding.reverseComplement(start, k);
            firstBase = findAppend(oriented, second);
        }
        StringBuilder sequence = new StringBuilder();
        sequence.append(DnaEncoding.decode(oriented, k));
        long lastKmer = oriented;
        if (firstBase != null) {
            sequence.append(DnaEncoding.decodeBase(firstBase.intValue()));
            lastKmer = ((lastKmer << 2) | firstBase) & kmerMask;
        }
        for (int i = 2; i < path.size(); i++) {
            long node = path.get(i);
            Long base = findAppend(lastKmer, node);
            if (base == null) {
                // 理论上不应发生（图中边必然存在某个方向组合）；防御性中断。
                break;
            }
            sequence.append(DnaEncoding.decodeBase(base.intValue()));
            lastKmer = ((lastKmer << 2) | base) & kmerMask;
        }
        double coverageSum = 0;
        for (long node : path) {
            coverageSum += coverage.getOrDefault(node, new long[1])[0];
        }
        double meanCoverage = coverageSum / path.size();
        return new Contig("contig-" + (index + 1), sequence.toString(), sequence.length(), meanCoverage);
    }

    /** 从 orientedKmer 向后延伸一位，若产物的 canonical 形式等于 neighbor 则返回该碱基。 */
    private Long findAppend(long orientedKmer, long neighbor) {
        for (int base = 0; base < 4; base++) {
            long product = ((orientedKmer << 2) | base) & kmerMask;
            if (canonical(product) == neighbor) {
                return (long) base;
            }
        }
        return null;
    }

    private static long edgeKey(long a, long b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return (lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private AssemblyStats computeStats(List<Contig> contigs) {
        long total = 0;
        long longest = 0;
        for (Contig c : contigs) {
            total += c.length();
            longest = Math.max(longest, c.length());
        }
        long n50 = 0;
        if (total > 0) {
            long cumulative = 0;
            for (Contig c : contigs) {
                cumulative += c.length();
                if (cumulative * 2 >= total) {
                    n50 = c.length();
                    break;
                }
            }
        }
        return new AssemblyStats(contigs.size(), total, longest, n50);
    }
}
