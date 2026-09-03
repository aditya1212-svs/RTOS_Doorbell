package com.aditya.rtos_doorbell.dto;

import java.util.List;

public record FaceDetectionUpdate(String deviceId, int frameWidth, int frameHeight, int facesDetected,
                                  List<FaceBoundingBox> faces, List<StoredFaceResponse> storedFaces) {}
