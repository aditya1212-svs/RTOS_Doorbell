package com.aditya.rtos_doorbell.dto;

import com.aditya.rtos_doorbell.entity.EventType;

import java.time.Instant;

/** Public visitor history representation; face embeddings are never included. */
public record VisitorEventResponse(Long id, Instant timestamp, String deviceId, EventType type,
                                   String recognizedName, boolean authorized, String frameUrl) {}
