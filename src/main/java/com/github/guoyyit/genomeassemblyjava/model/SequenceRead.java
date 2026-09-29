package com.github.guoyyit.genomeassemblyjava.model;

/**
 * 一条带质量信息的短读长序列（FASTQ 语义，qualities 可为空）。
 */
public record SequenceRead(String id, String bases, String qualities) {
}
