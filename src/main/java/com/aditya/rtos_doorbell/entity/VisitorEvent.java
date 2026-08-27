package com.aditya.rtos_doorbell.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "visitor_events", indexes = {
        @Index(name = "idx_visitor_event_timestamp", columnList = "timestamp"),
        @Index(name = "idx_visitor_event_device", columnList = "device_id"),
        @Index(name = "idx_visitor_event_type", columnList = "type")
})
public class VisitorEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Instant timestamp;
    @Column(name = "device_id", nullable = false, length = 128) private String deviceId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private EventType type;
    @Column(length = 255) private String recognizedName;
    @Column(nullable = false) private boolean authorized;
    @Column(length = 1024) private String frameUrl;

    protected VisitorEvent() {}
    public VisitorEvent(Instant timestamp, String deviceId, EventType type) {
        this.timestamp = timestamp; this.deviceId = deviceId; this.type = type;
    }
    public Long getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getDeviceId() { return deviceId; }
    public EventType getType() { return type; }
    public String getRecognizedName() { return recognizedName; }
    public boolean isAuthorized() { return authorized; }
    public String getFrameUrl() { return frameUrl; }
    public void complete(EventType type, String name, boolean authorized, String frameUrl) {
        this.type = type; this.recognizedName = name; this.authorized = authorized; this.frameUrl = frameUrl;
    }
}
