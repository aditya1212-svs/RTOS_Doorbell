package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Coordinates face embedding generation and matching. Model inference remains
 * in the long-lived Python worker; this service only owns persistence and API DTOs.
 */
@Service
public class FaceRecognitionService {
    private final FaceDetectionProcessManager processManager;
    private final PersonService personService;
    private final Executor executor;
    private final long maxImageBytes;
    private final int minNeighbors;
    private final double threshold;
    private final boolean enabled;

    @Autowired
    public FaceRecognitionService(FaceDetectionProcessManager processManager,
                                  PersonService personService,
                                  @Qualifier("faceDetectionExecutor") Executor executor,
                                  @Value("${face-detection.max-image-bytes:5242880}") long maxImageBytes,
                                  @Value("${face-detection.confidence-threshold:5}") int minNeighbors,
                                  @Value("${face-recognition.threshold:0.6}") double threshold,
                                  @Value("${face-recognition.enabled:true}") boolean enabled) {
        this.processManager = processManager;
        this.personService = personService;
        this.executor = executor;
        this.maxImageBytes = maxImageBytes;
        this.minNeighbors = minNeighbors;
        this.threshold = Double.isFinite(threshold) && threshold >= 0.0 ? threshold : 0.6;
        this.enabled = enabled;
    }

    /** Backward-compatible constructor for small unit/integration adapters. */
    public FaceRecognitionService(FaceDetectionProcessManager processManager,
                                  PersonService personService,
                                  Executor executor,
                                  long maxImageBytes,
                                  int minNeighbors,
                                  double threshold) {
        this(processManager, personService, executor, maxImageBytes, minNeighbors, threshold, true);
    }

    public CompletableFuture<FaceRegistrationResponse> register(MultipartFile frame, String name) {
        byte[] image = readAndValidate(frame);
        validateName(name);
        String contentType = frame.getContentType();
        return submit(() -> registerBytes(image, contentType, name, null));
    }

    public CompletableFuture<FaceRegistrationResponse> register(MultipartFile frame, UUID personId) {
        byte[] image = readAndValidate(frame);
        String contentType = frame.getContentType();
        return submit(() -> registerBytes(image, contentType, null, personId));
    }

    public CompletableFuture<FaceRecognitionResponse> recognize(MultipartFile frame) {
        byte[] image = readAndValidate(frame);
        String contentType = frame.getContentType();
        return submit(() -> recognizeBytes(image, contentType));
    }

    /** Synchronous provider entry point used only for a pending RING interaction. */
    public RecognitionResult recognizeForEvent(String deviceId, byte[] image) {
        if (image == null || image.length == 0) {
            throw new FaceRecognitionException(HttpStatus.BAD_REQUEST, "An image frame is required");
        }
        return bestMatch(recognizeBytes(image, "image/jpeg"));
    }

    public boolean isAvailable() {
        return enabled && processManager.isRecognitionAvailable();
    }

    public boolean isEnabled() {
        return enabled && processManager.isEnabled();
    }

    private <T> CompletableFuture<T> submit(java.util.function.Supplier<T> task) {
        try {
            return CompletableFuture.supplyAsync(task, executor);
        } catch (TaskRejectedException e) {
            throw new FaceRecognitionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face recognition queue is full", e);
        }
    }

    private FaceRegistrationResponse registerBytes(byte[] image, String contentType,
                                                    String name, UUID personId) {
        ensureRecognitionAvailable();
        Path temporaryImage = null;
        try {
            temporaryImage = writeTemporaryImage(image, contentType);
            FaceDetectionWorkerResponse detection = processManager.detect(temporaryImage, minNeighbors);
            List<FaceBoundingBox> faces = detection.faces() == null ? List.of() : detection.faces();
            if (faces.size() != 1) {
                throw new FaceRecognitionException(HttpStatus.BAD_REQUEST,
                        "Registration image must contain exactly one face.");
            }
            FaceBoundingBox bounds = faces.get(0);
            FaceDetectionWorkerResponse embedding = processManager.embedding(temporaryImage, bounds);
            List<Double> vector = embedding.embedding();
            if (vector == null || vector.isEmpty()) {
                throw new FaceRecognitionException(HttpStatus.BAD_GATEWAY,
                        "Face model did not return an embedding");
            }
            PersonResponse person = personId == null
                    ? personService.addEmbedding(name, vector)
                    : personService.addEmbedding(personId, vector);
            return new FaceRegistrationResponse(person, bounds);
        } catch (FaceDetectionException e) {
            throw e;
        } catch (IOException e) {
            throw new FaceRecognitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to prepare image for face registration", e);
        } finally {
            deleteTemporaryImage(temporaryImage);
        }
    }

    private FaceRecognitionResponse recognizeBytes(byte[] image, String contentType) {
        if (!enabled) {
            throw new FaceRecognitionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face recognition is disabled");
        }
        Path temporaryImage = null;
        try {
            temporaryImage = writeTemporaryImage(image, contentType);
            List<FaceEmbeddingReference> references = personService.embeddingReferences();
            FaceDetectionWorkerResponse worker;
            if (references.isEmpty()) {
                // Detection remains useful before anyone has been registered and
                // does not require loading the recognition model.
                worker = processManager.detect(temporaryImage, minNeighbors);
                return unknownResponse(worker);
            }
            ensureRecognitionAvailable();
            worker = processManager.recognize(temporaryImage, minNeighbors, references, threshold);
            return mapRecognition(worker);
        } catch (FaceDetectionException e) {
            throw e;
        } catch (IOException e) {
            throw new FaceRecognitionException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Unable to prepare image for recognition", e);
        } finally {
            deleteTemporaryImage(temporaryImage);
        }
    }

    private FaceRecognitionResponse mapRecognition(FaceDetectionWorkerResponse worker) {
        List<FaceRecognitionFace> results = new ArrayList<>();
        List<FaceRecognitionWorkerFace> workerResults = worker.recognitions();
        if (workerResults != null) {
            for (FaceRecognitionWorkerFace face : workerResults) {
                results.add(new FaceRecognitionFace(face.recognized(),
                        face.recognized() ? face.name() : null,
                        clampConfidence(face.confidence()), face.x(), face.y(), face.width(), face.height()));
            }
        }
        if (worker.faces() != null && results.size() < worker.faces().size()) {
            for (int index = results.size(); index < worker.faces().size(); index++) {
                results.add(unknown(worker.faces().get(index)));
            }
        } else if (workerResults == null && worker.faces() != null) {
            for (FaceBoundingBox face : worker.faces()) {
                results.add(unknown(face));
            }
        }
        return new FaceRecognitionResponse(results, valueOrZero(worker.frameWidth()),
                valueOrZero(worker.frameHeight()));
    }

    private FaceRecognitionResponse unknownResponse(FaceDetectionWorkerResponse worker) {
        List<FaceRecognitionFace> results = worker.faces() == null ? List.of()
                : worker.faces().stream().map(this::unknown).toList();
        return new FaceRecognitionResponse(results, valueOrZero(worker.frameWidth()),
                valueOrZero(worker.frameHeight()));
    }

    private FaceRecognitionFace unknown(FaceBoundingBox face) {
        return new FaceRecognitionFace(false, null, 0.0, face.x(), face.y(), face.width(), face.height());
    }

    private RecognitionResult bestMatch(FaceRecognitionResponse response) {
        FaceRecognitionFace best = response.faces().stream()
                .filter(FaceRecognitionFace::recognized)
                .max(Comparator.comparingDouble(FaceRecognitionFace::confidence))
                .orElse(null);
        List<FaceBoundingBox> boxes = response.faces().stream()
                .map(face -> new FaceBoundingBox(face.x(), face.y(), face.width(), face.height())).toList();
        if (best == null) {
            double confidence = response.faces().stream().mapToDouble(FaceRecognitionFace::confidence)
                    .max().orElse(0.0);
            return new RecognitionResult(null, false, confidence, boxes);
        }
        return new RecognitionResult(best.name(), true, best.confidence(), boxes);
    }

    private void ensureRecognitionAvailable() {
        if (!processManager.isRecognitionAvailable()) {
            throw new FaceRecognitionException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Face recognition model is unavailable");
        }
    }

    private byte[] readAndValidate(MultipartFile frame) {
        if (frame == null || frame.isEmpty()) {
            throw new FaceRecognitionException(HttpStatus.BAD_REQUEST, "An image frame is required");
        }
        if (frame.getSize() > maxImageBytes) {
            throw new FaceRecognitionException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Image exceeds maximum size of " + maxImageBytes + " bytes");
        }
        String type = frame.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new FaceRecognitionException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Frame must be an image");
        }
        try {
            return frame.getBytes();
        } catch (IOException e) {
            throw new FaceRecognitionException(HttpStatus.BAD_REQUEST, "Unable to read image", e);
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new FaceRecognitionException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (name.trim().length() > 128) {
            throw new FaceRecognitionException(HttpStatus.BAD_REQUEST, "name must be at most 128 characters");
        }
    }

    private Path writeTemporaryImage(byte[] image, String contentType) throws IOException {
        String suffix = contentType != null && contentType.toLowerCase(Locale.ROOT).contains("png") ? ".png" : ".jpg";
        Path temporaryImage = Files.createTempFile("doorbell-recognition-", suffix);
        try {
            Files.write(temporaryImage, image);
            return temporaryImage;
        } catch (IOException e) {
            Files.deleteIfExists(temporaryImage);
            throw e;
        }
    }

    private void deleteTemporaryImage(Path temporaryImage) {
        if (temporaryImage == null) return;
        try {
            Files.deleteIfExists(temporaryImage);
        } catch (IOException ignored) {
            // Temporary files are not part of the persistent face store.
        }
    }

    private double clampConfidence(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private int valueOrZero(Integer value) { return value == null ? 0 : value; }
}
