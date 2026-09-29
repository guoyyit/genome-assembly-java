package com.github.guoyyit.genomeassemblyjava.engine;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;

import java.util.Collection;

/**
 * 增量式 de Bruijn 图组装引擎：只维护可增量合并的 k-mer 覆盖度计数，
 * 图构建、纠错、简化与 contig 提取均为派生计算。
 */
public class KmerAssemblyEngine {

    public KmerAssemblyEngine(AssemblyConfig config) {
    }

    public void addRead(SequenceRead read) {
    }

    public void addReads(Collection<SequenceRead> reads) {
    }

    public AssemblyResult assemble(long readCount, long batchCount) {
        return null;
    }
}
