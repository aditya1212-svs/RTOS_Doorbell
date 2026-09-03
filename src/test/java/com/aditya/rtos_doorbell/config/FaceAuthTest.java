package com.aditya.rtos_doorbell.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the device gate that protects /api/face/** REST calls and
 * /topic/face/** subscriptions (TASK-002). The gate is disabled by default so
 * the simulator and phone keep working; enabling it restricts device-scoped
 * endpoints to an allowlist.
 */
class FaceAuthTest {

    @Test
    void disabledByDefaultAllowsEveryDevice() throws Exception {
        FaceAuthService auth = new FaceAuthService(false, "esp32-doorbell-01");
        FaceRestAuthInterceptor interceptor = new FaceRestAuthInterceptor(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/face/detect");

        assertTrue(interceptor.preHandle(request, response, null), "expect pass-through when disabled");
        assertEquals(200, response.getStatus());
    }

    @Test
    void enabledRejectsMissingDeviceIdentity() throws Exception {
        FaceAuthService auth = new FaceAuthService(true, "esp32-doorbell-01");
        FaceRestAuthInterceptor interceptor = new FaceRestAuthInterceptor(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/face/detect");

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void enabledRejectsDeviceOutsideAllowlist() throws Exception {
        FaceAuthService auth = new FaceAuthService(true, "esp32-doorbell-01");
        FaceRestAuthInterceptor interceptor = new FaceRestAuthInterceptor(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/face/recognize");
        request.addHeader("X-Device-Id", "other-doorbell");

        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void enabledAllowsAllowlistedDevice() throws Exception {
        FaceAuthService auth = new FaceAuthService(true, "esp32-doorbell-01");
        FaceRestAuthInterceptor interceptor = new FaceRestAuthInterceptor(auth);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/face/stored");
        request.addParameter("deviceId", "esp32-doorbell-01");

        assertTrue(interceptor.preHandle(request, response, null));
        assertEquals(200, response.getStatus());
    }
}