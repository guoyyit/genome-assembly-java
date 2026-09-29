package com.github.guoyyit.genomeassemblyjava.assembly;

public enum TaskStatus {
    /** Accepting further read batches. */
    ACTIVE,
    /** Closed for new batches; the last computed result is final. */
    CLOSED
}
