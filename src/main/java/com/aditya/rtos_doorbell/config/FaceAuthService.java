package com.aditya.rtos_doorbell.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Central gate for face endpoints and face topics, matching the way the rest of
 * the application identifies a source by a {@code deviceId}.
 *
 * <p>When {@code face.auth.enabled} is {@code true} and {@code face.auth.allowed-devices}
 * is configured, only requests/subscriptions for an allowed device are accepted. Otherwise
 * the backend keeps its current open posture so the simulator and phone clients keep working.
 */
@Service
public class FaceAuthService {

    private final boolean enabled;
    private final Set<String> allowedDevices;

    public FaceAuthService(
            @Value("${face.auth.enabled:false}") boolean enabled,
            @Value("${face.auth.allowed-devices:}") String allowedDevices) {
        this.enabled = enabled;
        this.allowedDevices = parseDevices(allowedDevices);
    }

    /** HTTP header used to carry a device identity (matches other API conventions). */
    public static final String DEVICE_HEADER = "X-Device-Id";

    /** Whether dev references enforcement is active. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Device ids that are allowed through the gate when enforcement is active. */
    public Set<String> allowedDevices() {
        return allowedDevices;
    }

    /** Read the device id from a header, falling back to the query/param value. */
    public String deviceId(HttpServletRequest request) {
        String header = request.getHeader(DEVICE_HEADER);
        if (header != null && !header.isBlank()) return header.trim();
        String param = request.getParameter("deviceId");
        return param == null ? null : param.trim();
    }

    /**
     * Enforce the configured allowlist. When the switch is off, every device is
     * allowed, preserving the pre-auth default behaviour.
     */
    public boolean allows(String deviceId) {
        if (!enabled) return true;
        return deviceId != null && allowedDevices.contains(deviceId);
    }

    private static Set<String> parseDevices(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split("\\s*,\\s*"))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}