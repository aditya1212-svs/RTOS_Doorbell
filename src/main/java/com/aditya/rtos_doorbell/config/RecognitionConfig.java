package com.aditya.rtos_doorbell.config;

import com.aditya.rtos_doorbell.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class RecognitionConfig {
    @Bean
    @ConditionalOnMissingBean(FaceRecognitionProvider.class)
    FaceRecognitionProvider faceRecognitionProvider() {
        return new NoOpFaceRecognitionProvider();
    }
}
