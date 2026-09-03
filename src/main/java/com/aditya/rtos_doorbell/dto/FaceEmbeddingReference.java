package com.aditya.rtos_doorbell.dto;

import java.util.List;

/** Wire-safe registered embedding sent to the Python worker for comparison. */
public record FaceEmbeddingReference(String personId, String name, List<Double> embedding) {}
