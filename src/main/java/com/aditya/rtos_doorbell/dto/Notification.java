package com.aditya.rtos_doorbell.dto;

import java.time.Instant;

public record Notification(String type, String message, String name, Instant timestamp, Long eventId) {
    public Notification(String type, String message, String name) {
        this(type, message, name, Instant.now(), null);
    }

    public Notification(String type, String message) {
        this(type, message, null);
    }
}
