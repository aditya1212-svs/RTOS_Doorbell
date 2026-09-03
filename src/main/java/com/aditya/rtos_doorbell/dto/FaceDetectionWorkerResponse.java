package com.aditya.rtos_doorbell.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Internal wire response from the managed Python worker. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaceDetectionWorkerResponse(String requestId, Integer facesDetected,
                                          List<FaceBoundingBox> faces, Integer frameWidth,
                                          Integer frameHeight, String error,
                                          List<Double> embedding, Integer embeddingDimensions,
                                          List<FaceRecognitionWorkerFace> recognitions) {
    public FaceDetectionWorkerResponse(String requestId, Integer facesDetected,
                                       List<FaceBoundingBox> faces, Integer frameWidth,
                                       Integer frameHeight, String error) {
        this(requestId, facesDetected, faces, frameWidth, frameHeight, error,
                null, null, null);
    }
}
