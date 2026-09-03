package com.aditya.rtos_doorbell.dto;

import java.time.Instant;
import java.util.UUID;

public record PersonResponse(UUID id, String name, Instant createdAt, int samples) {}
