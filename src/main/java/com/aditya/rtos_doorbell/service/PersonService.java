package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.*;
import com.aditya.rtos_doorbell.entity.*;
import com.aditya.rtos_doorbell.repository.PersonRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PersonService {
    private final PersonRepository repository;

    public PersonService(PersonRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<PersonResponse> list() {
        return repository.findAllWithEmbeddings().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public PersonResponse get(UUID id) {
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Person not found: " + id)));
    }

    @Transactional
    public PersonResponse create(String name) {
        String normalized = normalizeName(name);
        if (repository.findByNameIgnoreCase(normalized).isPresent()) {
            throw new IllegalArgumentException("A person named '" + normalized + "' already exists");
        }
        return toResponse(repository.save(new Person(normalized)));
    }

    @Transactional
    public void delete(UUID id) {
        Person person = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Person not found: " + id));
        repository.delete(person);
    }

    @Transactional
    public PersonResponse addEmbedding(String name, List<Double> embedding) {
        String normalized = normalizeName(name);
        Person person = repository.findByNameIgnoreCase(normalized)
                .orElseGet(() -> new Person(normalized));
        addEmbedding(person, embedding);
        return toResponse(repository.save(person));
    }

    @Transactional
    public PersonResponse addEmbedding(UUID id, List<Double> embedding) {
        Person person = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Person not found: " + id));
        addEmbedding(person, embedding);
        return toResponse(person);
    }

    @Transactional(readOnly = true)
    public List<FaceEmbeddingReference> embeddingReferences() {
        List<FaceEmbeddingReference> references = new ArrayList<>();
        for (Person person : repository.findAllWithEmbeddings()) {
            for (FaceEmbedding embedding : person.getEmbeddings()) {
                references.add(new FaceEmbeddingReference(person.getId().toString(), person.getName(),
                        EmbeddingCodec.decode(embedding.getEmbedding(), embedding.getDimensions())));
            }
        }
        return references;
    }

    private void addEmbedding(Person person, List<Double> embedding) {
        byte[] encoded = EmbeddingCodec.encode(embedding);
        person.addEmbedding(encoded, embedding.size());
    }

    private PersonResponse toResponse(Person person) {
        return new PersonResponse(person.getId(), person.getName(), person.getCreatedAt(),
                person.getEmbeddings().size());
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String normalized = name.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("name must be at most 128 characters");
        }
        return normalized;
    }
}
