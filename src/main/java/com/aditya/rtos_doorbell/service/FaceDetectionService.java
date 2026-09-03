package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.FaceBoundingBox;
import com.aditya.rtos_doorbell.dto.FaceDetectionResponse;
import com.aditya.rtos_doorbell.dto.FaceDetectionUpdate;
import com.aditya.rtos_doorbell.dto.FaceDetectionWorkerResponse;
import com.aditya.rtos_doorbell.dto.StoredFaceResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FaceDetectionService {
    private static final Logger log = LoggerFactory.getLogger(FaceDetectionService.class);
    private final FaceDetectionProcessManager processManager;
    private final Executor executor;
    private final long maxImageBytes;
    private final long intervalMs;
    private final int minNeighbors;
    private final StoredFaceService storedFaceService;
    private final SimpMessagingTemplate messaging;
    private final RecognitionService recognitionService;
    private final AtomicLong lastAcceptedAt = new AtomicLong(0);
    @Value("${face-recognition.enabled:true}")
    private boolean recognitionEnabled = true;

    @org.springframework.beans.factory.annotation.Autowired
    public FaceDetectionService(FaceDetectionProcessManager processManager,
                                @Qualifier("faceDetectionExecutor") Executor executor,
                                @Value("${face-detection.max-image-bytes:5242880}") long maxImageBytes,
                                @Value("${face-detection.interval-ms:200}") long intervalMs,
                                @Value("${face-detection.confidence-threshold:5}") int minNeighbors,
                                StoredFaceService storedFaceService,
                                SimpMessagingTemplate messaging,
                                RecognitionService recognitionService) {
        this.processManager = processManager;
        this.executor = executor;
        this.maxImageBytes = maxImageBytes;
        this.intervalMs = intervalMs;
        this.minNeighbors = minNeighbors;
        this.storedFaceService = storedFaceService;
        this.messaging = messaging;
        this.recognitionService = recognitionService;
    }

    public FaceDetectionService(FaceDetectionProcessManager processManager,
                                Executor executor, long maxImageBytes, long intervalMs,
                                int minNeighbors, StoredFaceService storedFaceService,
                                SimpMessagingTemplate messaging) {
        this(processManager, executor, maxImageBytes, intervalMs, minNeighbors,
                storedFaceService, messaging, null);
    }

    public CompletableFuture<FaceDetectionResponse> detect(MultipartFile frame, String deviceId, boolean store) {
        validate(frame);
        if (!processManager.isAvailable()) {
            throw new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face detector process is unavailable");
        }
        enforceInterval();
        final byte[] image;
        try {
            image = frame.getBytes();
        } catch (IOException e) {
            throw new FaceDetectionException(HttpStatus.BAD_REQUEST, "Unable to read image", e);
        }
        final String contentType = frame.getContentType();
        try {
            return CompletableFuture.supplyAsync(() -> detectAndPublish(image, contentType, deviceId, store), executor);
        } catch (TaskRejectedException e) {
            throw new FaceDetectionException(HttpStatus.SERVICE_UNAVAILABLE, "Face detection queue is full", e);
        }
    }

    public boolean isAvailable() {
        return processManager.isAvailable();
    }

    public boolean isEnabled() {
        return processManager.isEnabled();
    }

    public boolean isRecognitionAvailable() {
        return recognitionEnabled && processManager != null && processManager.isRecognitionAvailable();
    }

    private void enforceInterval() {
        if (intervalMs <= 0) return;
        long now = System.currentTimeMillis();
        while (true) {
            long previous = lastAcceptedAt.get();
            if (now - previous < intervalMs) {
                throw new FaceDetectionException(HttpStatus.TOO_MANY_REQUESTS, "Detection rate limited; retry later");
            }
            if (lastAcceptedAt.compareAndSet(previous, now)) return;
            now = System.currentTimeMillis();
        }
    }

    private FaceDetectionResponse detectAndPublish(byte[] image, String contentType,
                                                   String deviceId, boolean store) {
        Path temporaryImage = null;
        try {
            temporaryImage = writeTemporaryImage(image, contentType);
            FaceDetectionWorkerResponse worker = processManager.detect(temporaryImage, minNeighbors);
            List<FaceBoundingBox> faces = worker.faces() == null ? List.of() : worker.faces();
            List<StoredFaceResponse> storedFaces = store
                    ? storedFaceService.store(deviceId, image, contentType, faces)
                    : List.of();
            FaceDetectionResponse response = new FaceDetectionResponse(faces.size(), faces,
                    valueOrZero(worker.frameWidth()), valueOrZero(worker.frameHeight()), storedFaces);
            if (deviceId != null && !deviceId.isBlank()) {
                messaging.convertAndSend("/topic/face/" + deviceId, new FaceDetectionUpdate(deviceId,
                        response.frameWidth(), response.frameHeight(), response.facesDetected(), response.faces(),
                        response.storedFaces()));
                if (!faces.isEmpty() && recognitionService != null) {
                    try {
                        recognitionService.processDetectedPerson(deviceId, image);
                    } catch (RuntimeException e) {
                        // Detection remains useful even if persistence or the
                        // recognition queue is temporarily unavailable.
                        log.warn("Unable to start recognition for detected person on {}", deviceId, e);
                    }
                }
            }
            return response;
        } catch (FaceDetectionException e) {
            throw e;
        } catch (IOException e) {
            throw new FaceDetectionException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to prepare image for face detection", e);
        } finally {
            if (temporaryImage != null) {
                try {
                    Files.deleteIfExists(temporaryImage);
                } catch (IOException e) {
                    // The file is in the OS temporary directory and is no longer referenced.
                }
            }
        }
    }

    private Path writeTemporaryImage(byte[] image, String contentType) throws IOException {
        String suffix = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("png") ? ".png" : ".jpg";
        Path temporaryImage = Files.createTempFile("doorbell-frame-", suffix);
        try {
            Files.write(temporaryImage, image);
            return temporaryImage;
        } catch (IOException e) {
            Files.deleteIfExists(temporaryImage);
            throw e;
        }
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void validate(MultipartFile frame) {
        if (frame == null || frame.isEmpty()) {
            throw new FaceDetectionException(HttpStatus.BAD_REQUEST, "An image frame is required");
        }
        if (frame.getSize() > maxImageBytes) {
            throw new FaceDetectionException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image exceeds maximum size of " + maxImageBytes + " bytes");
        }
        String type = frame.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new FaceDetectionException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Frame must be an image");
        }
    }
}
