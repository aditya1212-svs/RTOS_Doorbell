package com.aditya.rtos_doorbell.service;

/** Adapter that keeps the existing FaceRecognitionProvider abstraction intact. */
public class PythonFaceRecognitionProvider implements FaceRecognitionProvider {
    private final FaceRecognitionService service;

    public PythonFaceRecognitionProvider(FaceRecognitionService service) {
        this.service = service;
    }

    @Override
    public RecognitionResult recognize(String deviceId, byte[] frame) {
        return service.recognizeForEvent(deviceId, frame);
    }
}
