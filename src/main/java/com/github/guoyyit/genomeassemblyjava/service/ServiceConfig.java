package com.github.guoyyit.genomeassemblyjava.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 服务装配：TaskManager 是本地单例，启动时从本地数据目录恢复全部任务，
 * 不依赖任何外部服务。
 */
@Configuration
public class ServiceConfig {

    @Bean(destroyMethod = "close")
    public TaskManager taskManager(
            @Value("${genome-assembly.data-directory:data/tasks}") String dataDirectory) {
        return new TaskManager(Path.of(dataDirectory));
    }
}
