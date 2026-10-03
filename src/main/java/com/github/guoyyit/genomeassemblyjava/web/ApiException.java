package com.github.guoyyit.genomeassemblyjava.web;

/**
 * 带 HTTP 状态码与稳定错误代码的业务异常。
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
