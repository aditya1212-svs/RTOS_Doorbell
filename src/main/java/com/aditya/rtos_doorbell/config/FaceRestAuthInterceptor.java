package com.aditya.rtos_doorbell.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * Protects device-scoped {@code /api/face/**} endpoints with the same device
 * gate the rest of the API applies. Only activated when {@code face.auth.enabled}
 * is {@code true} and an allowlist is configured; otherwise it is a no-op so the
 * simulator and phone clients keep working.
 */
@Component
public class FaceRestAuthInterceptor implements HandlerInterceptor {

    private final FaceAuthService auth;

    public FaceRestAuthInterceptor(FaceAuthService auth) {
        this.auth = auth;
    }

    /** Face endpoints that are read/write for a single device and so require a device identity. */
    private static final Set<String> DEVICE_SCOPED = Set.of(
            "/api/face/detect",
            "/api/face/recognize",
            "/api/face/stored");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!auth.isEnabled()) return true;

        String path = request.getRequestURI();
        if (!path.startsWith("/api/face/")) return true;
        if (!isDeviceScoped(path)) return true;                 // e.g. /health, register
        if (auth.allowedDevices().isEmpty()) return true;       // no allowlist configured yet

        String deviceId = auth.deviceId(request);
        if (deviceId == null || deviceId.isBlank()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "A device identity is required");
            return false;
        }
        if (!auth.allows(deviceId)) {
            response.sendError(HttpStatus.FORBIDDEN.value(), "Device is not authorized");
            return false;
        }
        return true;
    }

    private boolean isDeviceScoped(String path) {
        // /stored/… (list and per-object image) are both device-scoped.
        if (path.equals("/api/face/stored") || path.startsWith("/api/face/stored/")) return true;
        return DEVICE_SCOPED.contains(path);
    }
}