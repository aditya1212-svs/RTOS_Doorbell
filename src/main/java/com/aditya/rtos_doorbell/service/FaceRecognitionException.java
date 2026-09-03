package com.aditya.rtos_doorbell.service;

import org.springframework.http.HttpStatus;

public class FaceRecognitionException extends FaceDetectionException {

    public FaceRecognitionException(HttpStatus status, String message) {
        super(status, message);
    }

    public FaceRecognitionException(HttpStatus status, String message, Throwable cause) {
        super(status, message, cause);
    }
}
