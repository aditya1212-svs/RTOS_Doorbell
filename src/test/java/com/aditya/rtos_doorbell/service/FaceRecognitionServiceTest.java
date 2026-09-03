package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class FaceRecognitionServiceTest {
    @Test
    void registrationRequiresExactlyOneDetectedFace() {
        StubProcessManager manager = new StubProcessManager();
        manager.detection = new FaceDetectionWorkerResponse("d", 2,
                List.of(new FaceBoundingBox(1, 1, 10, 10), new FaceBoundingBox(20, 1, 10, 10)),
                100, 100, null);
        FaceRecognitionService service = service(manager, new StubPersonService());
        MockMultipartFile image = image();

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> service.register(image, "John").get());
        assertInstanceOf(FaceRecognitionException.class, exception.getCause());
        assertEquals(HttpStatus.BAD_REQUEST, ((FaceRecognitionException) exception.getCause()).getStatus());
    }

    @Test
    void recognitionReturnsPerFaceMatches() throws Exception {
        StubProcessManager manager = new StubProcessManager();
        manager.recognition = new FaceDetectionWorkerResponse("r", 2,
                List.of(new FaceBoundingBox(1, 2, 10, 11), new FaceBoundingBox(30, 4, 12, 13)),
                100, 80, null, null, null,
                List.of(new FaceRecognitionWorkerFace(true, "John", .91, 1, 2, 10, 11),
                        new FaceRecognitionWorkerFace(false, null, .21, 30, 4, 12, 13)));
        StubPersonService people = new StubPersonService();
        people.references = List.of(new FaceEmbeddingReference("p1", "John", List.of(.1, .2)));
        FaceRecognitionResponse response = service(manager, people).recognize(image()).get();

        assertEquals(2, response.facesDetected());
        assertTrue(response.faces().get(0).recognized());
        assertEquals("John", response.faces().get(0).name());
        assertFalse(response.faces().get(1).recognized());
    }

    @Test
    void unknownIsReturnedWhenNoPeopleAreRegistered() throws Exception {
        StubProcessManager manager = new StubProcessManager();
        manager.detection = new FaceDetectionWorkerResponse("d", 1,
                List.of(new FaceBoundingBox(2, 3, 10, 11)), 100, 80, null);
        FaceRecognitionResponse response = service(manager, new StubPersonService()).recognize(image()).get();

        assertEquals(1, response.facesDetected());
        assertFalse(response.faces().get(0).recognized());
        assertEquals(0.0, response.faces().get(0).confidence());
    }

    private FaceRecognitionService service(StubProcessManager manager, StubPersonService people) {
        return new FaceRecognitionService(manager, people, Runnable::run, 5_242_880, 5, .6);
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("frame", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3});
    }

    static class StubProcessManager extends FaceDetectionProcessManager {
        FaceDetectionWorkerResponse detection = new FaceDetectionWorkerResponse("d", 0, List.of(), 1, 1, null);
        FaceDetectionWorkerResponse embedding = new FaceDetectionWorkerResponse("e", null, null, null, null,
                null, List.of(.1, .2), 2, null);
        FaceDetectionWorkerResponse recognition = new FaceDetectionWorkerResponse("r", 0, List.of(), 1, 1, null);

        StubProcessManager() {
            super(new ObjectMapper(), true, "sh", "missing-worker", ".", 5_242_880, 100, 100);
        }

        @Override public boolean isAvailable() { return true; }
        @Override public boolean isRecognitionAvailable() { return true; }
        @Override public FaceDetectionWorkerResponse detect(Path path, int neighbors) { return detection; }
        @Override public FaceDetectionWorkerResponse embedding(Path path, FaceBoundingBox box) { return embedding; }
        @Override public FaceDetectionWorkerResponse recognize(Path path, int neighbors,
                                                                 List<FaceEmbeddingReference> refs, double threshold) {
            return recognition;
        }
    }

    static class StubPersonService extends PersonService {
        List<FaceEmbeddingReference> references = List.of();

        StubPersonService() { super(null); }

        @Override public List<FaceEmbeddingReference> embeddingReferences() { return references; }

        @Override public PersonResponse addEmbedding(String name, List<Double> embedding) {
            return new PersonResponse(UUID.randomUUID(), name, Instant.now(), 1);
        }

        @Override public PersonResponse addEmbedding(UUID id, List<Double> embedding) {
            return new PersonResponse(id, "John", Instant.now(), 1);
        }
    }
}
