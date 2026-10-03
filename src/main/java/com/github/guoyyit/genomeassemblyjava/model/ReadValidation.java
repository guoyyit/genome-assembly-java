package com.github.guoyyit.genomeassemblyjava.model;

import java.util.HashSet;
import java.util.Set;

/**
 * 批次与读段的参数校验：所有非法输入抛出带明确原因的 IllegalArgumentException，
 * 供统一异常处理转换为可区分的 400 错误响应。
 */
public final class ReadValidation {

    private static final int MAX_BATCH_ID_LENGTH = 128;
    private static final int MAX_READ_ID_LENGTH = 256;
    private static final int MAX_BASES_LENGTH = 1_000_000;
    private static final int MAX_READS_PER_BATCH = 10_000_000;

    private ReadValidation() {
    }

    public static void validate(ReadBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("request body is required");
        }
        String batchId = batch.batchId();
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId is required");
        }
        if (batchId.length() > MAX_BATCH_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "batchId length must not exceed " + MAX_BATCH_ID_LENGTH);
        }
        if (!batchId.chars().allMatch(ch ->
                (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                        || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_' || ch == '.')) {
            throw new IllegalArgumentException(
                    "batchId may only contain letters, digits, '-', '_' and '.'");
        }
        if (batch.reads() == null || batch.reads().isEmpty()) {
            throw new IllegalArgumentException("reads must not be empty");
        }
        if (batch.reads().size() > MAX_READS_PER_BATCH) {
            throw new IllegalArgumentException(
                    "a single batch must not contain more than " + MAX_READS_PER_BATCH + " reads");
        }
        Set<String> readIds = new HashSet<>();
        for (SequenceRead read : batch.reads()) {
            validateRead(read, readIds);
        }
    }

    private static void validateRead(SequenceRead read, Set<String> readIds) {
        if (read == null) {
            throw new IllegalArgumentException("read entries must not be null");
        }
        String id = read.id();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("each read requires a non-empty id");
        }
        if (id.length() > MAX_READ_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "read id length must not exceed " + MAX_READ_ID_LENGTH + ": " + id);
        }
        if (!readIds.add(id)) {
            throw new IllegalArgumentException("duplicate read id within batch: " + id);
        }
        String bases = read.bases();
        if (bases == null || bases.isEmpty()) {
            throw new IllegalArgumentException("bases are required for read: " + id);
        }
        if (bases.length() > MAX_BASES_LENGTH) {
            throw new IllegalArgumentException(
                    "read length must not exceed " + MAX_BASES_LENGTH + ": " + id);
        }
        for (int i = 0; i < bases.length(); i++) {
            char base = bases.charAt(i);
            if (isInvalidBase(base)) {
                throw new IllegalArgumentException(
                        "invalid base '" + base + "' at position " + i + " of read " + id
                                + "; only unambiguous bases A, C, G, T are accepted");
            }
        }
        String qualities = read.qualities();
        if (qualities != null && !qualities.isEmpty()) {
            if (qualities.length() != bases.length()) {
                throw new IllegalArgumentException(
                        "qualities length must equal bases length for read " + id);
            }
            for (int i = 0; i < qualities.length(); i++) {
                char q = qualities.charAt(i);
                if (q < '!' || q > '~') {
                    throw new IllegalArgumentException(
                            "invalid Phred quality character at position " + i + " of read " + id
                                    + "; allowed ASCII range is '!' (33) through '~' (126)");
                }
            }
        }
    }

    private static boolean isInvalidBase(char base) {
        char upper = Character.toUpperCase(base);
        return upper != 'A' && upper != 'C' && upper != 'G' && upper != 'T';
    }
}
