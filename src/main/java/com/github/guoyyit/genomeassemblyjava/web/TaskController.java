package com.github.guoyyit.genomeassemblyjava.web;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.service.AssemblyTask;
import com.github.guoyyit.genomeassemblyjava.service.TaskManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 组装任务 REST 接口。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskManager taskManager;

    public TaskController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping
    public ResponseEntity<TaskSnapshot> createTask(@RequestBody(required = false) AssemblyConfig config) {
        return null;
    }

    @GetMapping
    public List<TaskSnapshot> listTasks() {
        return List.of();
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot getTask(@PathVariable String taskId) {
        return null;
    }

    @PostMapping("/{taskId}/batches")
    public TaskSnapshot submitBatch(@PathVariable String taskId, @RequestBody ReadBatch batch) {
        return null;
    }

    @PostMapping("/{taskId}/assemble")
    public TaskSnapshot assemble(@PathVariable String taskId) {
        return null;
    }

    @GetMapping("/{taskId}/result")
    public ResponseEntity<AssemblyResult> getResult(@PathVariable String taskId) {
        return null;
    }

    static Map<String, String> errorBody(String message) {
        return Map.of("error", message);
    }
}
