package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.dto.FaceDetectionResponse;
import com.aditya.rtos_doorbell.dto.FaceDetectionHealthResponse;
import com.aditya.rtos_doorbell.dto.FaceRecognitionResponse;
import com.aditya.rtos_doorbell.dto.FaceRegistrationResponse;
import com.aditya.rtos_doorbell.dto.StoredFaceResponse;
import com.aditya.rtos_doorbell.service.FaceDetectionService;
import com.aditya.rtos_doorbell.service.FaceRecognitionException;
import com.aditya.rtos_doorbell.service.FaceRecognitionService;
import com.aditya.rtos_doorbell.service.StoredFaceService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.concurrent.CompletableFuture;
import java.util.*;

@RestController
@RequestMapping("/api/face")
public class FaceDetectionController {
    private final FaceDetectionService service;
    private final StoredFaceService storedFaceService;
    private final FaceRecognitionService recognitionService;

    public FaceDetectionController(FaceDetectionService service, StoredFaceService storedFaceService) {
        this(service, storedFaceService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public FaceDetectionController(FaceDetectionService service, StoredFaceService storedFaceService,
                                   ObjectProvider<FaceRecognitionService> recognitionServices) {
        this.service = service;
        this.storedFaceService = storedFaceService;
        this.recognitionService = recognitionServices.getIfAvailable();
    }
    @PostMapping(value = "/detect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<FaceDetectionResponse>> detect(
            @RequestPart("frame") MultipartFile frame,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "false") boolean store) {
        return service.detect(frame, deviceId, store).thenApply(ResponseEntity::ok);
    }

    @GetMapping("/health")
    public FaceDetectionHealthResponse health() {
        return new FaceDetectionHealthResponse(service.isAvailable(), service.isRecognitionAvailable());
    }

    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<FaceRegistrationResponse>> register(
            @RequestParam String name, @RequestPart("frame") MultipartFile frame) {
        requireRecognitionService();
        return recognitionService.register(frame, name).thenApply(ResponseEntity::ok);
    }

    @PostMapping(value = "/register/{personId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<FaceRegistrationResponse>> registerSample(
            @PathVariable UUID personId, @RequestPart("frame") MultipartFile frame) {
        requireRecognitionService();
        return recognitionService.register(frame, personId).thenApply(ResponseEntity::ok);
    }

    @PostMapping(value = "/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CompletableFuture<ResponseEntity<FaceRecognitionResponse>> recognize(
            @RequestPart("frame") MultipartFile frame) {
        requireRecognitionService();
        return recognitionService.recognize(frame).thenApply(ResponseEntity::ok);
    }

    @GetMapping("/stored")
    public List<StoredFaceResponse> stored(@RequestParam String deviceId,
                                           @RequestParam(defaultValue = "20") int limit) {
        return storedFaceService.list(deviceId, limit);
    }

    @GetMapping("/stored/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id) {
        var storedFace = storedFaceService.image(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(storedFace.getContentType()))
                .body(storedFace.getImage());
    }

    private void requireRecognitionService() {
        if (recognitionService == null) {
            throw new FaceRecognitionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face recognition is not configured");
        }
    }
}
