package com.aditya.rtos_doorbell.service;

public interface FaceRecognitionProvider {
    RecognitionResult recognize(String deviceId, byte[] frame);
}
