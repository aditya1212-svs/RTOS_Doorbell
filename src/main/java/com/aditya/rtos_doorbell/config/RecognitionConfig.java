package com.aditya.rtos_doorbell.config;

import com.aditya.rtos_doorbell.service.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class RecognitionConfig {
    @Bean
    @ConditionalOnProperty(name = "face-recognition.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(FaceRecognitionProvider.class)
    FaceRecognitionProvider pythonFaceRecognitionProvider(FaceRecognitionService service) {
        return new PythonFaceRecognitionProvider(service);
    }

    @Bean
    @ConditionalOnProperty(name = "face-recognition.enabled", havingValue = "false")
    @ConditionalOnMissingBean(FaceRecognitionProvider.class)
    FaceRecognitionProvider faceRecognitionProvider() {
        return new NoOpFaceRecognitionProvider();
    }
}
