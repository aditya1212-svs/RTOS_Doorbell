package com.aditya.rtos_doorbell.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record FaceDetectionResponse(int facesDetected, List<FaceBoundingBox> faces, int frameWidth,
                                    int frameHeight, List<StoredFaceResponse> storedFaces) {
    public FaceDetectionResponse(int facesDetected, List<FaceBoundingBox> faces) {
        this(facesDetected, faces, 0, 0, List.of());
    }
}
