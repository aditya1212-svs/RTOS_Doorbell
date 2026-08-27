package com.aditya.rtos_doorbell.dto;

import com.aditya.rtos_doorbell.entity.EventType;
import jakarta.validation.constraints.*;
import java.time.Instant;

public record DeviceEventRequest(
        @NotBlank String deviceId,
        @NotNull Instant timestamp,
        @NotNull EventType type) {}
