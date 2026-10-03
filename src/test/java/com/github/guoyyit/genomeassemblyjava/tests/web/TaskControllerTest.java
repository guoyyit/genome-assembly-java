package com.github.guoyyit.genomeassemblyjava.tests.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskControllerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;


    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("genome-assembly.data-directory",
                () -> {
                    try {
                        return Files.createTempDirectory("web-test-data").toString();
                    } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    void healthEndpointIsServed() {
        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/health", String.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("UP"));
    }

    @Test
    void endToEndApiFlow() {
        ResponseEntity<JsonNode> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks", json(configBody()), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        JsonNode task = requireBody(created);
        String taskId = task.get("taskId").asText();
        assertFalse(taskId.isBlank());
        assertEquals("CREATED", task.get("state").asText());

        Random random = new Random(101);
        String reference = SequencingSimulator.randomReference(random, 500);
        List<SequenceRead> reads = SequencingSimulator.generateReads(
                reference, 80, 30, 0.01, random);
        ReadBatch batch = new ReadBatch("web-batch-1", reads);

        ResponseEntity<JsonNode> accepted = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches", batch, JsonNode.class);
        assertEquals(HttpStatus.CREATED, accepted.getStatusCode());
        JsonNode receipt = requireBody(accepted);
        assertEquals(false, receipt.get("duplicate").asBoolean());
        assertEquals(reads.size(), receipt.get("snapshot").get("readCount").asLong());

        ResponseEntity<JsonNode> duplicate = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches", batch, JsonNode.class);
        assertEquals(HttpStatus.OK, duplicate.getStatusCode());
        assertEquals(true, requireBody(duplicate).get("duplicate").asBoolean());
        assertEquals(reads.size(),
                requireBody(duplicate).get("snapshot").get("readCount").asLong(),
                "duplicate submission must not be counted twice");

        ResponseEntity<JsonNode> assembled = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/assemble", null, JsonNode.class);
        assertEquals(HttpStatus.OK, assembled.getStatusCode());
        assertEquals("COMPLETED", requireBody(assembled).get("state").asText());

        ResponseEntity<JsonNode> result = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/result", JsonNode.class);
        assertEquals(HttpStatus.OK, result.getStatusCode());
        JsonNode resultBody = requireBody(result);
        assertTrue(resultBody.get("contigs").size() >= 1);
        JsonNode stats = resultBody.get("stats");
        assertTrue(stats.get("contigCount").asLong() >= 1);
        assertTrue(stats.get("totalLength").asLong() >= reference.length() * 0.85);
        assertTrue(stats.get("longestLength").asLong() >= 100);
        assertTrue(stats.get("n50").asLong() > 0);

        ResponseEntity<JsonNode> fetched = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId, JsonNode.class);
        assertEquals(HttpStatus.OK, fetched.getStatusCode());
        assertEquals("COMPLETED", requireBody(fetched).get("state").asText());

        ResponseEntity<JsonNode> list = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/tasks", JsonNode.class);
        assertEquals(HttpStatus.OK, list.getStatusCode());
        assertTrue(requireBody(list).isArray());
    }

    @Test
    void rejectsInvalidInputsWithDistinctErrors() {
        ResponseEntity<JsonNode> missingTask = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/tasks/does-not-exist", JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, missingTask.getStatusCode());
        assertEquals("task_not_found", requireBody(missingTask).get("error").asText());

        ResponseEntity<JsonNode> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks", json(configBody()), JsonNode.class);
        String taskId = requireBody(created).get("taskId").asText();

        ReadBatch emptyBatch = new ReadBatch("empty", List.of());
        ResponseEntity<JsonNode> empty = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches", emptyBatch, JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, empty.getStatusCode());
        assertEquals("invalid_request", requireBody(empty).get("error").asText());

        ReadBatch badBase = new ReadBatch("bad",
                List.of(new SequenceRead("r1", "ACGTXACGT", "IIIIIIIII")));
        ResponseEntity<JsonNode> invalid = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches", badBase, JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, invalid.getStatusCode());
        assertTrue(requireBody(invalid).get("message").asText().contains("invalid base"));

        ResponseEntity<JsonNode> malformed = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches",
                json("{not-json"), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.getStatusCode());
        assertEquals("malformed_json", requireBody(malformed).get("error").asText());

        ResponseEntity<JsonNode> badConfig = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks",
                json("{\"kmerSize\":1,\"minCoverage\":1,\"maxTipLength\":1,"
                        + "\"qualityTrimThreshold\":0,\"minReadLength\":1}"),
                JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, badConfig.getStatusCode());

        assertEquals(HttpStatus.OK,
                restTemplate.getForEntity("http://localhost:" + port + "/health", JsonNode.class).getStatusCode());
    }

    @Test
    void concurrentSubmissionsAndQueriesRemainConsistent() throws Exception {
        ResponseEntity<JsonNode> created = restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks", json(configBody()), JsonNode.class);
        String taskId = requireBody(created).get("taskId").asText();

        ReadBatch seed = new ReadBatch("dup-target",
                List.of(new SequenceRead("seed",
                        "ACGTACGTACGTACGTACGTACGT", "I".repeat(24))));
        assertEquals(HttpStatus.CREATED, restTemplate.postForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId + "/batches",
                seed, JsonNode.class).getStatusCode());

        int uniqueBatches = 60;
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < uniqueBatches; i++) {
            int index = i;
            pool.submit(() -> {
                awaitLatch(start);
                ReadBatch batch = new ReadBatch("concurrent-" + index,
                        List.of(new SequenceRead("c" + index,
                                "ACGTACGTACGTACGTACGTACGT", "I".repeat(24))));
                ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                        "http://localhost:" + port + "/api/tasks/" + taskId + "/batches",
                        batch, JsonNode.class);
                assertEquals(HttpStatus.CREATED, response.getStatusCode());
            });
        }
        for (int i = 0; i < uniqueBatches; i++) {
            pool.submit(() -> {
                awaitLatch(start);
                JsonNode snapshot = requireBody(restTemplate.getForEntity(
                        "http://localhost:" + port + "/api/tasks/" + taskId, JsonNode.class));
                assertEquals(snapshot.get("readCount").asLong(),
                        snapshot.get("batchCount").asLong());
            });
        }
        for (int i = 0; i < 6; i++) {
            pool.submit(() -> {
                awaitLatch(start);
                ReadBatch duplicate = new ReadBatch("dup-target",
                        List.of(new SequenceRead("seed",
                                "ACGTACGTACGTACGTACGTACGT", "I".repeat(24))));
                ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                        "http://localhost:" + port + "/api/tasks/" + taskId + "/batches",
                        duplicate, JsonNode.class);
                assertEquals(HttpStatus.OK, response.getStatusCode());
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        JsonNode finalSnapshot = requireBody(restTemplate.getForEntity(
                "http://localhost:" + port + "/api/tasks/" + taskId, JsonNode.class));
        assertEquals(uniqueBatches + 1, finalSnapshot.get("batchCount").asLong());
        assertEquals(uniqueBatches + 1, finalSnapshot.get("readCount").asLong());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static String configBody() {
        return "{\"kmerSize\":21,\"minCoverage\":2,\"maxTipLength\":42,"
                + "\"qualityTrimThreshold\":20,\"minReadLength\":21}";
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static JsonNode requireBody(ResponseEntity<JsonNode> response) {
        JsonNode body = response.getBody();
        assertNotNull(body, "response body must not be empty");
        assertFalse(body.isMissingNode(), "response body must be a valid JSON document");
        return body;
    }
}
