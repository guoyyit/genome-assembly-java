package com.github.guoyyit.genomeassemblyjava.tests;

import com.github.guoyyit.genomeassemblyjava.web.TaskController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ApplicationTests {

    @Autowired
    private TaskController taskController;

    @Test
    public void contentLoad() {
        assertNotNull(taskController, "Spring context should load and wire the controller");
    }
}
