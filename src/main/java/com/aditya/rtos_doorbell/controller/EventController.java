package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.dto.DeviceEventRequest;
import com.aditya.rtos_doorbell.service.VisitorEventService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/event")
public class EventController {
    private final VisitorEventService service;
    public EventController(VisitorEventService service) { this.service = service; }
    @PostMapping
    public ResponseEntity<Void> accept(@Valid @RequestBody DeviceEventRequest request) {
        service.accept(request);
        return ResponseEntity.accepted().build();
    }
}
