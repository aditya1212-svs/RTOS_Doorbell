package com.aditya.rtos_doorbell.dto;

/** Public recognition result for one detected face. */
public record FaceRecognitionFace(boolean recognized, String name, double confidence,
                                 int x, int y, int width, int height) {}
