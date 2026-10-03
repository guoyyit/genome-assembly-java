package com.github.guoyyit.genomeassemblyjava.service;

/**
 * 任务标识不存在时抛出；对应 HTTP 404。
 */
public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(String taskId) {
        super("task not found: " + taskId);
    }
}
