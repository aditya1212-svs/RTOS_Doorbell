package com.aditya.rtos_doorbell.controller;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.format.DateTimeParseException;
import java.util.NoSuchElementException;
import com.aditya.rtos_doorbell.service.FaceDetectionException;
import com.aditya.rtos_doorbell.service.FaceRecognitionException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({MethodArgumentNotValidException.class, DateTimeParseException.class,
            IllegalArgumentException.class})
    ResponseEntity<String> badRequest(Exception ex) {
        return ResponseEntity.badRequest().body("Invalid request: " + ex.getMessage());
    }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<String> notFound(NoSuchElementException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }
    @ExceptionHandler(FaceDetectionException.class)
    ResponseEntity<String> detectionFailure(FaceDetectionException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getMessage());
    }

    @ExceptionHandler(FaceRecognitionException.class)
    ResponseEntity<String> recognitionFailure(FaceRecognitionException ex) {
        return ResponseEntity.status(ex.getStatus()).body(ex.getMessage());
    }
}
