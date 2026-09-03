package com.aditya.rtos_doorbell.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "persons", uniqueConstraints = @UniqueConstraint(name = "uk_person_name", columnNames = "name"))
public class Person {
    @Id
    private UUID id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "person", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    private final List<FaceEmbedding> embeddings = new ArrayList<>();

    protected Person() {}

    public Person(String name) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.createdAt = Instant.now();
    }

    public void addEmbedding(byte[] vector, int dimensions) {
        embeddings.add(new FaceEmbedding(this, vector, dimensions, Instant.now()));
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public List<FaceEmbedding> getEmbeddings() { return embeddings; }
}
