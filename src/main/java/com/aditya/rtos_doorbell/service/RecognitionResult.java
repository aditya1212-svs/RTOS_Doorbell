package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.FaceBoundingBox;

import java.util.List;

public record RecognitionResult(String name, boolean authorized, double confidence,
                                List<FaceBoundingBox> faces) {
    public RecognitionResult(String name, boolean authorized) {
        this(name, authorized, 0.0, List.of());
    }

    public RecognitionResult(String name, boolean authorized, double confidence) {
        this(name, authorized, confidence, List.of());
    }

    public boolean matched() { return name != null && !name.isBlank(); }
}
