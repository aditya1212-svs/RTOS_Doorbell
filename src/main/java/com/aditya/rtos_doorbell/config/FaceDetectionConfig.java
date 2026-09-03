package com.aditya.rtos_doorbell.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;

@Configuration
public class FaceDetectionConfig {
    @Bean(name = "faceDetectionExecutor")
    Executor faceDetectionExecutor(@Value("${face-detection.processing-threads:2}") int threads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, threads));
        executor.setMaxPoolSize(Math.max(1, threads));
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("face-detection-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
