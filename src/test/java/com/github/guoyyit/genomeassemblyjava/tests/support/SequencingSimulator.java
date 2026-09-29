package com.github.guoyyit.genomeassemblyjava.tests.support;

import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.List;
import java.util.Random;

/**
 * 模拟测序数据生成器：从参考序列按指定覆盖度与错误率生成短读长及质量串。
 */
public final class SequencingSimulator {

    private SequencingSimulator() {
    }

    public static String randomReference(Random random, int length) {
        return "";
    }

    public static List<SequenceRead> generateReads(String reference,
                                                   int readLength,
                                                   double coverage,
                                                   double errorRate,
                                                   Random random) {
        return List.of();
    }
}
