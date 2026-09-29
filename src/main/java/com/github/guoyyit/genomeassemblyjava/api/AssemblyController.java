package com.github.guoyyit.genomeassemblyjava.api;

import com.github.guoyyit.genomeassemblyjava.assembly.AssemblyService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for submitting reads and querying assembly tasks.
 */
@RestController
@RequestMapping("/api/assembly/tasks")
public class AssemblyController {

    private final AssemblyService service;

    public AssemblyController(AssemblyService service) {
        this.service = service;
    }
}
