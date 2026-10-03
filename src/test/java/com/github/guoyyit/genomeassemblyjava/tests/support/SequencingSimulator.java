package com.github.guoyyit.genomeassemblyjava.tests.support;

import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟测序数据生成器：从参考序列按指定覆盖度与错误率生成短读长及质量串。
 *
 * <p>正确碱基赋予高质量（Q30..Q40），错误碱基赋予低质量（Q2..Q9），
 * 质量串因此携带了真实的错误信号，供引擎的质量过滤使用。
 */
public final class SequencingSimulator {

    private static final char[] BASES = {'A', 'C', 'G', 'T'};
    private static final int PHRED_OFFSET = 33;

    private SequencingSimulator() {
    }

    public static String randomReference(Random random, int length) {
        char[] chars = new char[length];
        for (int i = 0; i < length; i++) {
            chars[i] = BASES[random.nextInt(BASES.length)];
        }
        return new String(chars);
    }

    /**
     * 按覆盖度（总碱基数 / 参考长度）生成读段，错误率为每碱基替换概率。
     * 读段随机起始、均匀覆盖参考序列，错误碱基的质量显著低于正确碱基。
     */
    public static List<SequenceRead> generateReads(String reference,
                                                   int readLength,
                                                   double coverage,
                                                   double errorRate,
                                                   Random random) {
        int effectiveReadLength = Math.min(readLength, reference.length());
        int readCount = (int) Math.round(coverage * reference.length() / effectiveReadLength);
        int maxStart = reference.length() - effectiveReadLength;
        List<SequenceRead> reads = new ArrayList<>();
        for (int r = 0; r < readCount; r++) {
            int start = maxStart == 0 ? 0 : random.nextInt(maxStart + 1);
            char[] bases = new char[effectiveReadLength];
            char[] qualities = new char[effectiveReadLength];
            for (int i = 0; i < effectiveReadLength; i++) {
                char trueBase = reference.charAt(start + i);
                if (random.nextDouble() < errorRate) {
                    char errorBase;
                    do {
                        errorBase = BASES[random.nextInt(BASES.length)];
                    } while (errorBase == trueBase);
                    bases[i] = errorBase;
                    qualities[i] = (char) (PHRED_OFFSET + 2 + random.nextInt(8));
                } else {
                    bases[i] = trueBase;
                    qualities[i] = (char) (PHRED_OFFSET + 30 + random.nextInt(11));
                }
            }
            reads.add(new SequenceRead("read-" + r, new String(bases), new String(qualities)));
        }
        return reads;
    }
}
