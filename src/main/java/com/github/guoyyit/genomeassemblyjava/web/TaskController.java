package com.github.guoyyit.genomeassemblyjava.web;

import com.github.guoyyit.genomeassemblyjava.model.AssemblyConfig;
import com.github.guoyyit.genomeassemblyjava.model.AssemblyResult;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
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
 * 组装任务 REST 接口。路径与参数结构保持兼容：
 * <ul>
 *   <li>POST /api/tasks — 创建任务（请求体为可选的 AssemblyConfig JSON）</li>
 *   <li>GET  /api/tasks — 列出全部任务快照</li>
 *   <li>GET  /api/tasks/{taskId} — 查询单个任务快照</li>
 *   <li>POST /api/tasks/{taskId}/batches — 提交批次（ReadBatch JSON）</li>
 *   <li>POST /api/tasks/{taskId}/assemble — 触发组装</li>
 *   <li>GET  /api/tasks/{taskId}/result — 获取组装结果</li>
 * </ul>
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
            @RequestBody(required = false) AssemblyConfig config) {
        AssemblyTask task = taskManager.createTask(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(task.snapshot());
    }

    @GetMapping
    public List<TaskSnapshot> listTasks() {
        return taskManager.listSnapshots();
    }

    @GetMapping("/{taskId}")
    public TaskSnapshot getTask(@PathVariable String taskId) {
        return taskManager.getTask(taskId).snapshot();
    }

    @PostMapping("/{taskId}/batches")
    public TaskSnapshot submitBatch(@PathVariable String taskId, @RequestBody ReadBatch batch) {
        return taskManager.getTask(taskId).submitBatch(batch);
    }

    @PostMapping("/{taskId}/assemble")
    public TaskSnapshot assemble(@PathVariable String taskId) {
        return taskManager.getTask(taskId).assemble();
    }

    @GetMapping("/{taskId}/result")
    public ResponseEntity<AssemblyResult> getResult(@PathVariable String taskId) {
        AssemblyTask task = taskManager.getTask(taskId);
        if (!task.hasResult()) {
            throw new IllegalStateException("task has not been assembled yet: " + taskId);
        }
        return ResponseEntity.ok(task.getResult());
    }
}
