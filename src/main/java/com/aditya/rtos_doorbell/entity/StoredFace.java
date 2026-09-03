package com.aditya.rtos_doorbell.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "stored_faces", indexes = {
        @Index(name = "idx_stored_face_device_detected", columnList = "device_id,detected_at")
})
public class StoredFace {
    @Id
    private UUID id;
    @Column(name = "device_id", nullable = false, length = 128)
    private String deviceId;
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
    @Column(nullable = false)
    private int x;
    @Column(nullable = false)
    private int y;
    @Column(nullable = false)
    private int width;
    @Column(nullable = false)
    private int height;
    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] image;

    protected StoredFace() {}

    public StoredFace(String deviceId, Instant detectedAt, int x, int y, int width, int height,
                      String contentType, byte[] image) {
        this.id = UUID.randomUUID();
        this.deviceId = deviceId;
        this.detectedAt = detectedAt;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.contentType = contentType;
        this.image = image;
    }

    public UUID getId() { return id; }
    public String getDeviceId() { return deviceId; }
    public Instant getDetectedAt() { return detectedAt; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public String getContentType() { return contentType; }
    public byte[] getImage() { return image == null ? null : image.clone(); }
}
