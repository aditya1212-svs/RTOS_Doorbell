package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.dto.PersonCreateRequest;
import com.aditya.rtos_doorbell.dto.PersonResponse;
import com.aditya.rtos_doorbell.service.PersonService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/person")
public class PersonController {
    private final PersonService service;

    public PersonController(PersonService service) {
        this.service = service;
    }

    @GetMapping
    public List<PersonResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public PersonResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    public PersonResponse create(@Valid @RequestBody PersonCreateRequest request) {
        return service.create(request.name());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
