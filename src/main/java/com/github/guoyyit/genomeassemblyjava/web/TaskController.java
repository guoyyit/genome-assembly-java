package com.github.guoyyit.genomeassemblyjava.web;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.BatchReceipt;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.ReadValidation;
import com.github.guoyyit.genomeassemblyjava.model.TaskSnapshot;
import com.github.guoyyit.genomeassemblyjava.service.AssemblyTask;
import com.github.guoyyit.genomeassemblyjava.service.TaskManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 组装任务 REST 接口。所有路径与请求结构保持向后兼容：
 * POST /api/tasks、GET /api/tasks、GET /api/tasks/{id}、
 * POST /api/tasks/{id}/batches、POST /api/tasks/{id}/assemble、
 * GET /api/tasks/{id}/result。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskManager taskManager;

    public TaskController(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @PostMapping
    public ResponseEntity<TaskSnapshot> createTask(
            @RequestBody(required = false) CreateTaskRequest request) {
        AssemblyConfig config = (request == null ? new CreateTaskRequest(null, null, null, null, null)
                : request).toConfig();
        AssemblyTask task = taskManager.createTask(null, config);
        return ResponseEntity.status(HttpStatus.CREATED).body(task.snapshot());
    }

    @GetMapping
    public List<TaskSnapshot> listTasks() {
        return taskManager.listSnapshots();
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot getTask(@PathVariable String taskId) {
        return requireTask(taskId).snapshot();
    }

    @PostMapping("/{taskId}/batches")
    public ResponseEntity<BatchReceipt> submitBatch(@PathVariable String taskId,
                                                    @RequestBody ReadBatch batch) {
        ReadValidation.validate(batch);
        AssemblyTask task = requireTask(taskId);
        BatchReceipt receipt = task.submitBatch(batch);
        return ResponseEntity.status(receipt.duplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(receipt);
    }

    @PostMapping("/{taskId}/assemble")
    public TaskSnapshot assemble(@PathVariable String taskId) {
        return requireTaskIdFound(taskId).assemble();
    }

    @GetMapping("/{taskId}/result")
    public ResponseEntity<AssemblyResult> getResult(@PathVariable String taskId) {
        AssemblyTask task = requireTaskIdFound(taskId);
        if (!task.isCompleted()) {
            throw new ApiException(HttpStatus.CONFLICT.value(), "not_completed",
                    "assembly result is not available; state=" + task.snapshot().state());
        }
        return ResponseEntity.ok(task.getResult());
    }

    private AssemblyTask requireTask(String taskId) {
        if (!taskManager.exists(taskId)) {
            throw new ApiException(HttpStatus.NOT_FOUND.value(), "task_not_found",
                    "task not found: " + taskId);
        }
        return taskManager.getTask(taskId);
    }

    private AssemblyTask requireTaskIdFound(String taskId) {
        return requireTask(taskId);
    }
}
