package com.github.guoyyit.genomeassemblyjava.service;

/**
 * 重复提交同一批次（batchId 已受理）时抛出；对应 HTTP 409。
 */
public class DuplicateBatchException extends RuntimeException {

    public DuplicateBatchException(String batchId) {
        super("batch already accepted: " + batchId);
    }
}
