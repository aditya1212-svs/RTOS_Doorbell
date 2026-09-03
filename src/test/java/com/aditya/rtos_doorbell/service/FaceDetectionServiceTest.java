package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.FaceDetectionWorkerResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class FaceDetectionServiceTest {
    @Test
    void rejectsUnsupportedMediaTypeBeforeQueueingWork() {
        FaceDetectionService service = service(new StubProcessManager());
        MockMultipartFile frame = new MockMultipartFile("frame", "frame.txt", MediaType.TEXT_PLAIN_VALUE,
                new byte[]{1});

        FaceDetectionException exception = assertThrows(FaceDetectionException.class,
                () -> service.detect(frame, null, false));
        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, exception.getStatus());
    }

    @Test
    void rejectsOversizedFrameBeforeQueueingWork() {
        FaceDetectionService service = new FaceDetectionService(new StubProcessManager(), Runnable::run,
                1, 0, 5, null, null);
        MockMultipartFile frame = new MockMultipartFile("frame", "frame.jpg", MediaType.IMAGE_JPEG_VALUE,
                new byte[]{1, 2});

        FaceDetectionException exception = assertThrows(FaceDetectionException.class,
                () -> service.detect(frame, null, false));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.getStatus());
    }

    @Test
    void mapsWorkerTimeoutToGatewayTimeout() throws Exception {
        StubProcessManager manager = new StubProcessManager();
        manager.failure = new FaceDetectionException(HttpStatus.GATEWAY_TIMEOUT, "Face detector timed out");
        FaceDetectionService service = service(manager);
        MockMultipartFile frame = new MockMultipartFile("frame", "frame.jpg", MediaType.IMAGE_JPEG_VALUE,
                new byte[]{1});

        ExecutionException exception = assertThrows(ExecutionException.class,
                () -> service.detect(frame, null, false).get());
        assertInstanceOf(FaceDetectionException.class, exception.getCause());
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, ((FaceDetectionException) exception.getCause()).getStatus());
    }

    private FaceDetectionService service(StubProcessManager manager) {
        return new FaceDetectionService(manager, Runnable::run, 5_242_880, 0, 5, null, null);
    }

    private static class StubProcessManager extends FaceDetectionProcessManager {
        private FaceDetectionException failure;

        StubProcessManager() {
            super(new ObjectMapper(), true, "sh", "missing-worker", ".", 5_242_880,
                    100, 100);
        }

        @Override
        public boolean isAvailable() { return true; }

        @Override
        public FaceDetectionWorkerResponse detect(Path imagePath, int minNeighbors) {
            if (failure != null) throw failure;
            return new FaceDetectionWorkerResponse("test", 0, List.of(), 1, 1, null);
        }
    }
}
