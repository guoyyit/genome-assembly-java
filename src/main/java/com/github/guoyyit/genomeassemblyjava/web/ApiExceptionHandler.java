package com.github.guoyyit.genomeassemblyjava.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一错误响应：非法参数、找不到任务、状态冲突、请求体无法解析各自返回
 * 可区分的状态码与 error code；异常处理本身不改变任何任务状态。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApiException(ApiException exception) {
        return build(exception.status(), exception.code(), exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException exception) {
        return build(HttpStatus.BAD_REQUEST.value(), "invalid_request", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException exception) {
        return build(HttpStatus.CONFLICT.value(), "invalid_state", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            HttpMessageNotReadableException exception) {
        return build(HttpStatus.BAD_REQUEST.value(), "malformed_json",
                "request body is missing or is not valid JSON");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        LOG.error("unhandled request exception", exception);
        return build(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal_error",
                "unexpected server error: " + exception.getClass().getSimpleName());
    }

    private ResponseEntity<Map<String, Object>> build(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", code);
        body.put("message", message);
        body.put("timestamp", OffsetDateTime.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
