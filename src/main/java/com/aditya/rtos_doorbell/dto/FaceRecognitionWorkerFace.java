package com.aditya.rtos_doorbell.dto;

/** Internal face-recognition result returned by the Python worker. */
public record FaceRecognitionWorkerFace(boolean recognized, String name, double confidence,
                                       int x, int y, int width, int height) {}
