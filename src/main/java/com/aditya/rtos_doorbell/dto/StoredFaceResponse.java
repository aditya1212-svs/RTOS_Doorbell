package com.aditya.rtos_doorbell.dto;

import java.time.Instant;
import java.util.UUID;

public record StoredFaceResponse(UUID id, String deviceId, Instant detectedAt, FaceBoundingBox bounds,
                                 String imageUrl) {}
