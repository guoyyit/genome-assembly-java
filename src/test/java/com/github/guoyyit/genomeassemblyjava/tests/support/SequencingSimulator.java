package com.github.guoyyit.genomeassemblyjava.tests.support;

import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟测序数据生成器：从参考序列按指定覆盖度与错误率生成短读长及 Phred+33 质量串。
 *
 * <p>错误碱基给低质量值（Q3..Q8），正确碱基给高质量值（Q30..Q39），使质量信息
 * 真正可用于区分错误碱基。默认 15% 的错误碱基伪装成高质量，用于验证 minCoverage
 * 频次纠错路径；所有随机决策均来自传入的 Random，输出完全确定。</p>
 */
public final class SequencingSimulator {

    private static final char[] BASES = {'A', 'C', 'G', 'T'};

    private SequencingSimulator() {
    }

    public static String randomReference(Random random, int length) {
        char[] reference = new char[length];
        for (int i = 0; i < length; i++) {
            reference[i] = BASES[random.nextInt(4)];
        }
        return new String(reference);
    }

    public static List<SequenceRead> generateReads(String reference,
                                                   int readLength,
                                                   double coverage,
                                                   double errorRate,
                                                   Random random) {
        return generateReads(reference, readLength, coverage, errorRate, 0.15, random);
    }

    public static List<SequenceRead> generateReads(String reference,
                                                   int readLength,
                                                   double coverage,
                                                   double errorRate,
                                                   double highQualityErrorFraction,
                                                   Random random) {
        return generateReads(reference, readLength, coverage, errorRate,
                highQualityErrorFraction, "r", random);
    }

    public static List<SequenceRead> generateReads(String reference,
                                                   int readLength,
                                                   double coverage,
                                                   double errorRate,
                                                   double highQualityErrorFraction,
                                                   String idPrefix,
                                                   Random random) {
        List<SequenceRead> reads = new ArrayList<>();
        int readCount = (int) Math.ceil((coverage * reference.length()) / readLength);
        for (int index = 0; index < readCount; index++) {
            int start = random.nextInt(reference.length() - readLength + 1);
            String fragment = reference.substring(start, start + readLength);
            if (random.nextBoolean()) {
                fragment = reverseComplement(fragment);
            }
            char[] bases = fragment.toCharArray();
            char[] qualities = new char[readLength];
            for (int i = 0; i < readLength; i++) {
                boolean mutate = random.nextDouble() < errorRate;
                if (mutate) {
                    char erroneous = bases[i];
                    while (erroneous == bases[i]) {
                        erroneous = BASES[random.nextInt(4)];
                    }
                    bases[i] = erroneous;
                    boolean pretendHigh = random.nextDouble() < highQualityErrorFraction;
                    qualities[i] = (char) ('!' + (pretendHigh
                            ? 30 + random.nextInt(10)
                            : 3 + random.nextInt(6)));
                } else {
                    qualities[i] = (char) ('!' + 30 + random.nextInt(10));
                }
            }
            reads.add(new SequenceRead(String.format("%s%06d", idPrefix, index),
                    new String(bases), new String(qualities)));
        }
        return reads;
    }

    public static String reverseComplement(String sequence) {
        char[] result = new char[sequence.length()];
        for (int i = 0; i < sequence.length(); i++) {
            result[i] = complement(sequence.charAt(sequence.length() - 1 - i));
        }
        return new String(result);
    }

    private static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'C' -> 'G';
            case 'G' -> 'C';
            case 'T' -> 'A';
            default -> throw new IllegalArgumentException("invalid base: " + base);
        };
    }
}
