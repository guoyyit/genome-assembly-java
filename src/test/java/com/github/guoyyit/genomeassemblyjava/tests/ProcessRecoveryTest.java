package com.github.guoyyit.genomeassemblyjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过 java -jar 真实启动服务进程，在批次受理后、组装前强制 kill -9，
 * 再用相同命令重启，验证已受理批次不重不漏、追加批次后结论与不中断时一致。
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessRecoveryTest {

    private static final int PORT = 18099;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Path jar;

    @BeforeAll
    static void buildJar() throws Exception {
        Path expectedJar = Path.of("target").toAbsolutePath()
                .resolve("genome-assembly-java-1.0-SNAPSHOT.jar");
        if (!Files.isRegularFile(expectedJar)) {
            ProcessBuilder builder = new ProcessBuilder(
                    "mvn", "-q", "-o", "package", "-DskipTests");
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            assertTrue(exit == 0, "failed to build executable jar:\n" + output);
        }
        assertTrue(Files.isRegularFile(expectedJar),
                "executable jar must exist after package: " + expectedJar);
        jar = expectedJar;
    }

    @Test
    void recoversFromForcedProcessKillWithoutLossOrDuplication() throws Exception {
        Path dataDir = Files.createTempDirectory("genome-recovery-");
        Path logA = Files.createTempFile("genome-a-", ".log");
        Path builtJar = jar;

        Random random = new Random(4242);
        String reference = SequencingSimulator.randomReference(random, 400);
        List<SequenceRead> firstBatch = SequencingSimulator.generateReads(
                reference, 70, 25, 0.01, 0.15, "before", new Random(11));
        List<SequenceRead> secondBatch = SequencingSimulator.generateReads(
                reference, 70, 25, 0.01, 0.15, "after", new Random(12));

        Process firstProcess = startService(builtJar, dataDir, logA);
        try {
            awaitHealthy();
            String taskId = createTask();
            submitBatch(taskId, "batch-before-kill", firstBatch, 201);
            submitBatch(taskId, "batch-before-kill", firstBatch, 200);
        } finally {
            firstProcess.destroyForcibly();
            firstProcess.waitFor();
        }

        Path logB = Files.createTempFile("genome-b-", ".log");
        Process secondProcess = startService(builtJar, dataDir, logB);
        String expectedResultJson;
        try {
            awaitHealthy();
            String taskId = listSingleTaskId();
            JsonNode snapshot = getJson("/api/tasks/" + taskId);
            assertEquals(firstBatch.size(), snapshot.get("readCount").asLong(),
                    "restart must replay every accepted read exactly once");
            assertEquals(1, snapshot.get("batchCount").asLong());

            submitBatch(taskId, "batch-after-kill", secondBatch, 201);
            postNoBody("/api/tasks/" + taskId + "/assemble");
            expectedResultJson = awaitResult(taskId);

            JsonNode result = MAPPER.readTree(expectedResultJson);
            assertTrue(result.get("stats").get("contigCount").asLong() >= 1);
            assertTrue(result.get("stats").get("totalLength").asLong()
                    >= reference.length() * 0.8);
            for (JsonNode contig : result.get("contigs")) {
                String sequence = contig.get("sequence").asText();
                assertTrue(reference.contains(sequence)
                                || reference.contains(
                                SequencingSimulator.reverseComplement(sequence)),
                        "recovered assembly must not invent sequence: " + sequence);
            }
        } finally {
            secondProcess.destroyForcibly();
            secondProcess.waitFor();
        }

        List<SequenceRead> combined = new java.util.ArrayList<>(firstBatch);
        combined.addAll(secondBatch);
        String oneShotJson = assembleWithoutInterruption(builtJar, combined);
        assertEquals(MAPPER.readTree(oneShotJson).get("stats"),
                MAPPER.readTree(expectedResultJson).get("stats"),
                "recovered incremental assembly must match uninterrupted one-shot assembly");
    }

    private String assembleWithoutInterruption(Path jar, List<SequenceRead> reads) throws Exception {
        Path freshData = Files.createTempDirectory("genome-control-");
        Path log = Files.createTempFile("genome-control-", ".log");
        Process process = startService(jar, freshData, log);
        try {
            awaitHealthy();
            String taskId = createTask();
            submitBatch(taskId, "all-reads", reads, 201);
            postNoBody("/api/tasks/" + taskId + "/assemble");
            return awaitResult(taskId);
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private Process startService(Path jar, Path dataDir, Path log) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/java",
                "-jar", jar.toString());
        builder.environment().put("GENOME_ASSEMBLY_PORT", String.valueOf(PORT));
        builder.environment().put("GENOME_ASSEMBLY_DATA_DIR", dataDir.toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private void awaitHealthy() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri("/health")).GET().build();
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("UP")) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("service did not become healthy");
    }

    private String createTask() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/tasks"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"kmerSize\":21,\"minCoverage\":2,\"maxTipLength\":42,"
                                + "\"qualityTrimThreshold\":20,\"minReadLength\":21}"))
                .build();
        JsonNode body = sendExpecting(request, 201);
        return body.get("taskId").asText();
    }

    private String listSingleTaskId() throws Exception {
        JsonNode tasks = getJson("/api/tasks");
        assertTrue(tasks.isArray() && tasks.size() == 1);
        return tasks.get(0).get("taskId").asText();
    }

    private void submitBatch(String taskId, String batchId, List<SequenceRead> reads,
                             int expectedStatus) throws Exception {
        ReadBatch batch = new ReadBatch(batchId, reads);
        HttpRequest request = HttpRequest.newBuilder(
                        uri("/api/tasks/" + taskId + "/batches"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(batch)))
                .build();
        sendExpecting(request, expectedStatus);
    }

    private void postNoBody(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        sendExpecting(request, 200);
    }

    private String awaitResult(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> response = rawGet("/api/tasks/" + taskId + "/result");
            if (response.statusCode() == 200) {
                return response.body();
            }
            assertEquals(409, response.statusCode());
            Thread.sleep(500);
        }
        throw new IllegalStateException("assembly did not finish in time");
    }

    private JsonNode getJson(String path) throws Exception {
        return MAPPER.readTree(rawGet(path).body());
    }

    private HttpResponse<String> rawGet(String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode sendExpecting(HttpRequest request, int expectedStatus) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(),
                "unexpected response: " + response.body());
        return MAPPER.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + PORT + path);
    }
}
