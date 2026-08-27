package com.aditya.rtos_doorbell.service;

public class NoOpFaceRecognitionProvider implements FaceRecognitionProvider {
    @Override public RecognitionResult recognize(String deviceId, byte[] frame) {
        return new RecognitionResult(null, false);
    }
}
