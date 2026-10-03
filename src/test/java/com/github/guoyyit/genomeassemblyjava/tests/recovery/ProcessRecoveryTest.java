package com.github.guoyyit.genomeassemblyjava.tests.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.Application;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程级中断恢复测试：用真实的 {@code java} 命令启动服务，
 * 在批次受理后、组装完成前的任意时刻 {@code kill -9} 强杀进程，
 * 重启后验证数据不丢、结论一致、幂等生效。
 */
class ProcessRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @TempDir
    Path tempDir;

    private Process process;

    @AfterEach
    void tearDown() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    @Test
    void survivesKill9AndRestartWithConsistentConclusion() throws Exception {
        Path dataDir = tempDir.resolve("data");
        int port = freePort();
        String baseUrl = "http://localhost:" + port;

        // 用真实启动方式（java 命令）启动服务
        process = startProcess(dataDir, port);
        waitForStartup(baseUrl, process);

        // 准备数据：参考序列 + 3 个批次
        Random random = new Random(77);
        String reference = SequencingSimulator.randomReference(random, 1200);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 30, 0.01, random);
        List<ReadBatch> batches = splitBatches(reads, 3);

        // 创建任务并提交前两个批次
        String taskId = createTask(baseUrl);
        submitBatch(baseUrl, taskId, batches.get(0));
        submitBatch(baseUrl, taskId, batches.get(1));

        // 触发一次组装，得到"中断前结论"
        assemble(baseUrl, taskId);
        JsonNode resultBeforeKill = getResult(baseUrl, taskId);
        assertTrue(resultBeforeKill.get("contigs").size() >= 1);

        // 任意时刻强制终止进程（kill -9）
        process.destroyForcibly();
        assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));

        // 重启进程
        process = startProcess(dataDir, port);
        waitForStartup(baseUrl, process);

        // 重启后任务仍在，批次不丢
        JsonNode snapshot = getTask(baseUrl, taskId);
        assertEquals(2, snapshot.get("batchCount").asInt());
        assertEquals(reads.size() * 2 / 3, snapshot.get("readCount").asInt());

        // 重启后重新组装，结论必须与中断前逐字节一致
        assemble(baseUrl, taskId);
        JsonNode resultAfterRestart = getResult(baseUrl, taskId);
        assertEquals(resultBeforeKill, resultAfterRestart,
                "conclusion after restart must be identical to before kill");

        // 继续提交第三个批次，最终结论必须与一次性提交完全一致
        submitBatch(baseUrl, taskId, batches.get(2));
        assemble(baseUrl, taskId);
        JsonNode resultWithIncrementalBatches = getResult(baseUrl, taskId);

        String allAtOnceTask = createTask(baseUrl);
        for (ReadBatch batch : batches) {
            submitBatch(baseUrl, allAtOnceTask, batch);
        }
        assemble(baseUrl, allAtOnceTask);
        JsonNode resultAllAtOnce = getResult(baseUrl, allAtOnceTask);

        assertEquals(resultAllAtOnce, resultWithIncrementalBatches,
                "incremental result after restart must match all-at-once result");

        // 重复提交同一批次 → 409 可区分响应，且不计入
        HttpResponse<String> duplicateResponse = submitBatchRaw(baseUrl, taskId, batches.get(2));
        assertEquals(409, duplicateResponse.statusCode());
        JsonNode snapshotAfterDuplicate = getTask(baseUrl, taskId);
        assertEquals(3, snapshotAfterDuplicate.get("batchCount").asInt());
    }

    // ------------------------------------------------------------------
    // 进程管理
    // ------------------------------------------------------------------

    private Process startProcess(Path dataDir, int port) throws IOException {
        String javaHome = System.getProperty("java.home");
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(
                javaHome + "/bin/java",
                "-cp", classpath,
                "-Dgenome.data-dir=" + dataDir,
                "-Dserver.port=" + port,
                Application.class.getName());
        builder.redirectOutput(ProcessBuilder.Redirect.to(tempDir.resolve("process-" + port + ".log").toFile()));
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private void waitForStartup(String baseUrl, Process proc) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) {
                throw new IllegalStateException("process exited during startup, see log");
            }
            try {
                HttpResponse<String> response =
                        HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                // 连接被拒绝，服务尚未就绪
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("service did not start within 60 seconds");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    // ------------------------------------------------------------------
    // HTTP 调用
    // ------------------------------------------------------------------

    private String createTask(String baseUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode(), "createTask should return 201");
        return MAPPER.readTree(response.body()).get("taskId").asText();
    }

    private void submitBatch(String baseUrl, String taskId, ReadBatch batch) throws Exception {
        HttpResponse<String> response = submitBatchRaw(baseUrl, taskId, batch);
        assertEquals(200, response.statusCode(),
                "submitBatch should return 200, got " + response.statusCode() + ": " + response.body());
    }

    private HttpResponse<String> submitBatchRaw(String baseUrl, String taskId, ReadBatch batch)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId + "/batches"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(batch)))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assemble(String baseUrl, String taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId + "/assemble"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "assemble should return 200");
        assertEquals("COMPLETED", MAPPER.readTree(response.body()).get("state").asText());
    }

    private JsonNode getTask(String baseUrl, String taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId))
                .GET()
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
        return MAPPER.readTree(response.body());
    }

    private JsonNode getResult(String baseUrl, String taskId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/tasks/" + taskId + "/result"))
                .GET()
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "getResult should return 200");
        assertNotNull(response.body());
        return MAPPER.readTree(response.body());
    }

    private static List<ReadBatch> splitBatches(List<SequenceRead> reads, int parts) {
        int[] bounds = new int[parts + 1];
        for (int i = 0; i <= parts; i++) {
            bounds[i] = (int) ((long) reads.size() * i / parts);
        }
        List<ReadBatch> batches = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            batches.add(new ReadBatch("batch-" + i, reads.subList(bounds[i], bounds[i + 1])));
        }
        return batches;
    }
}
