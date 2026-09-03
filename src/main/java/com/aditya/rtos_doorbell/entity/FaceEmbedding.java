package com.aditya.rtos_doorbell.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "face_embeddings", indexes = @Index(name = "idx_face_embedding_person", columnList = "person_id"))
public class FaceEmbedding {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "person_id", nullable = false)
    private Person person;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private int dimensions;

    /** IEEE-754 double values; this is explicit binary data, not a Java-serialized object. */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(nullable = false)
    private byte[] embedding;

    protected FaceEmbedding() {}

    public FaceEmbedding(Person person, byte[] embedding, int dimensions, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.person = person;
        this.embedding = embedding == null ? null : embedding.clone();
        this.dimensions = dimensions;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public Person getPerson() { return person; }
    public Instant getCreatedAt() { return createdAt; }
    public int getDimensions() { return dimensions; }
    public byte[] getEmbedding() { return embedding == null ? null : embedding.clone(); }
}
