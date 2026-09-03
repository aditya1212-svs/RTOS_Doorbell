package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.config.FaceAuthService;
import com.aditya.rtos_doorbell.dto.FaceBoundingBox;
import com.aditya.rtos_doorbell.dto.FaceDetectionResponse;
import com.aditya.rtos_doorbell.service.FaceDetectionException;
import com.aditya.rtos_doorbell.service.FaceDetectionService;
import com.aditya.rtos_doorbell.service.StoredFaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FaceDetectionController.class)
@Import(FaceDetectionControllerTest.TestConfig.class)
class FaceDetectionControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StubFaceDetectionService faceDetectionService;

    @BeforeEach
    void resetStub() {
        faceDetectionService.response = null;
        faceDetectionService.failure = null;
        faceDetectionService.available = false;
    }

    @Test
    void returnsAllDetectedFaceBoxes() throws Exception {
        faceDetectionService.response = new FaceDetectionResponse(2, List.of(
                new FaceBoundingBox(120, 80, 150, 150), new FaceBoundingBox(400, 100, 140, 140)));

        MvcResult result = mockMvc.perform(multipart("/api/face/detect")
                        .file(new MockMultipartFile("frame", "door.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1})))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.facesDetected").value(2))
                .andExpect(jsonPath("$.faces[0].x").value(120))
                .andExpect(jsonPath("$.faces[1].width").value(140));
    }

    @Test
    void returnsAnEmptyFaceListWhenNoFaceExists() throws Exception {
        faceDetectionService.response = new FaceDetectionResponse(0, List.of());

        MvcResult result = mockMvc.perform(multipart("/api/face/detect")
                        .file(new MockMultipartFile("frame", "empty.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1})))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facesDetected").value(0))
                .andExpect(jsonPath("$.faces").isEmpty());
    }

    @Test
    void returnsBadGatewayWhenDetectorIsUnavailable() throws Exception {
        faceDetectionService.failure = new FaceDetectionException(HttpStatus.BAD_GATEWAY,
                "Face detection service unavailable");

        mockMvc.perform(multipart("/api/face/detect")
                        .file(new MockMultipartFile("frame", "door.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1})))
                .andExpect(status().isBadGateway())
                .andExpect(content().string("Face detection service unavailable"));
    }

    @Test
    void exposesChildProcessHealth() throws Exception {
        faceDetectionService.available = true;

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/face/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        StubFaceDetectionService faceDetectionService() {
            return new StubFaceDetectionService();
        }

        @Bean
        StoredFaceService storedFaceService() {
            return new StoredFaceService(null);
        }

        // The MVC slice loads FaceRestAuthInterceptor (a HandlerInterceptor) but
        // not FaceAuthService (a @Service). Provide a disabled gate so the context
        // is resolvable within the slice.
        @Bean
        FaceAuthService faceAuthService() {
            return new FaceAuthService(false, "");
        }
    }

    static class StubFaceDetectionService extends FaceDetectionService {
        private FaceDetectionResponse response;
        private FaceDetectionException failure;
        private boolean available;

        StubFaceDetectionService() {
            super(null, Runnable::run, 5_242_880, 0, 5, null, null);
        }

        @Override
        public CompletableFuture<FaceDetectionResponse> detect(MultipartFile frame, String deviceId, boolean store) {
            if (failure != null) throw failure;
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }
    }
}
