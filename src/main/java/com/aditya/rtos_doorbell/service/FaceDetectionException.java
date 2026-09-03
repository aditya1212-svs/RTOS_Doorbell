package com.aditya.rtos_doorbell.service;

import org.springframework.http.HttpStatus;

public class FaceDetectionException extends RuntimeException {
    private final HttpStatus status;

    public FaceDetectionException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public FaceDetectionException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public HttpStatus getStatus() {
        return status;
    }
}
