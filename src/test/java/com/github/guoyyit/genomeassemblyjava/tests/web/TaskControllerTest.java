package com.github.guoyyit.genomeassemblyjava.tests.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.guoyyit.genomeassemblyjava.model.ReadBatch;
import com.github.guoyyit.genomeassemblyjava.model.SequenceRead;
import com.github.guoyyit.genomeassemblyjava.tests.support.SequencingSimulator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "genome.data-dir=target/test-data-web")
@AutoConfigureMockMvc
class TaskControllerTest {

    private static final Path DATA_DIR = Path.of("target/test-data-web");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void cleanDataDir() throws IOException {
        if (Files.exists(DATA_DIR)) {
            Files.walkFileTree(DATA_DIR, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    @Test
    void endToEndApiFlow() throws Exception {
        String taskId = createTask("{}");

        String reference = SequencingSimulator.randomReference(new Random(1), 800);
        List<SequenceRead> reads =
                SequencingSimulator.generateReads(reference, 100, 30, 0.01, new Random(2));
        List<ReadBatch> batches = splitBatches(reads, 3);

        long cumulativeReads = 0;
        for (int i = 0; i < batches.size(); i++) {
            cumulativeReads += batches.get(i).reads().size();
            mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(batches.get(i))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.batchCount").value(i + 1))
                    .andExpect(jsonPath("$.readCount").value(cumulativeReads))
                    .andExpect(jsonPath("$.state").value("RUNNING"));
        }

        // 重复提交同一批次 → 409，错误体可区分
        mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batches.get(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // 触发组装
        mockMvc.perform(post("/api/tasks/{taskId}/assemble", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.stats.contigCount").isNumber())
                .andExpect(jsonPath("$.stats.totalLength").isNumber())
                .andExpect(jsonPath("$.stats.longestLength").isNumber())
                .andExpect(jsonPath("$.stats.n50").isNumber());

        // 查询结果：结构完整、非空
        MvcResult result = mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.config").exists())
                .andExpect(jsonPath("$.contigs").isArray())
                .andExpect(jsonPath("$.contigs[0].id").isNotEmpty())
                .andExpect(jsonPath("$.contigs[0].sequence").isNotEmpty())
                .andExpect(jsonPath("$.contigs[0].length").isNumber())
                .andExpect(jsonPath("$.contigs[0].meanCoverage").isNumber())
                .andExpect(jsonPath("$.stats.contigCount").isNumber())
                .andExpect(jsonPath("$.stats.totalLength").isNumber())
                .andExpect(jsonPath("$.stats.n50").isNumber())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.isBlank(), "result body must not be empty");
        assertTrue(objectMapper.readTree(body).get("contigs").size() >= 1,
                "must produce at least one contig");

        // 查询任务快照
        mockMvc.perform(get("/api/tasks/{taskId}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));

        // 任务列表
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void rejectsInvalidRequestsWithDistinguishableErrors() throws Exception {
        // 不存在的任务 → 404
        mockMvc.perform(get("/api/tasks/unknown-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("unknown-id")));

        mockMvc.perform(post("/api/tasks/unknown-id/batches")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReadBatch("x", List.of(new SequenceRead("r", "ACGT", null))))))
                .andExpect(status().isNotFound());

        String taskId = createTask("{}");

        // 空批次 → 400
        mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReadBatch("empty", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // 非法碱基 → 400
        mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReadBatch("bad", List.of(new SequenceRead("r", "ACGTN", null))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // 空白 batchId → 400
        mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ReadBatch("  ", List.of(new SequenceRead("r", "ACGT", null))))))
                .andExpect(status().isBadRequest());

        // 质量串长度不匹配 → 400
        ReadBatch badQuality = new ReadBatch("q",
                List.of(new SequenceRead("r", "ACGT", "III")));
        mockMvc.perform(post("/api/tasks/{taskId}/batches", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badQuality)))
                .andExpect(status().isBadRequest());

        // 未组装就查结果 → 409
        mockMvc.perform(get("/api/tasks/{taskId}/result", taskId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // 非法参数（kmerSize 为偶数）→ 400
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kmerSize\":20}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());

        // 非法参数（minCoverage 为 0）→ 400
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minCoverage\":0}"))
                .andExpect(status().isBadRequest());

        // 拒绝后服务仍可用：再建一个合法任务
        String newTaskId = createTask("{}");
        mockMvc.perform(get("/api/tasks/{taskId}", newTaskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CREATED"));
    }

    private String createTask(String configJson) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.taskId").isNotEmpty())
                .andExpect(jsonPath("$.state").value("CREATED"))
                .andExpect(jsonPath("$.readCount").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("taskId").asText();
    }

    private static List<ReadBatch> splitBatches(List<SequenceRead> reads, int parts) {
        int[] bounds = new int[parts + 1];
        for (int i = 0; i <= parts; i++) {
            bounds[i] = (int) ((long) reads.size() * i / parts);
        }
        java.util.ArrayList<ReadBatch> batches = new java.util.ArrayList<>();
        for (int i = 0; i < parts; i++) {
            batches.add(new ReadBatch("batch-" + i, reads.subList(bounds[i], bounds[i + 1])));
        }
        return batches;
    }
}
