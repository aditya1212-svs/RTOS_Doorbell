package com.aditya.rtos_doorbell.dto;

public record FaceDetectionHealthResponse(boolean available, boolean recognitionAvailable) {
    public FaceDetectionHealthResponse(boolean available) {
        this(available, false);
    }
}
