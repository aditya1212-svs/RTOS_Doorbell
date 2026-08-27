package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.entity.*;
import com.aditya.rtos_doorbell.repository.VisitorEventRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.time.*;

@Service
public class RecognitionService {
    private final VisitorEventRepository repository;
    private final FaceRecognitionProvider provider;
    private final java.util.concurrent.ConcurrentHashMap<String, VisitorEvent> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private final SimpMessagingTemplate messaging;

    public RecognitionService(VisitorEventRepository repository, FaceRecognitionProvider provider,
                              SimpMessagingTemplate messaging) {
        this.repository = repository; this.provider = provider;
        this.messaging = messaging;
    }
    public void markPending(VisitorEvent event) { pending.put(event.getDeviceId(), event); }
    public void processLatest(String deviceId, byte[] frame) {
        VisitorEvent event = pending.remove(deviceId);
        if (event != null && event.getTimestamp().isAfter(Instant.now().minusSeconds(300))) complete(event, frame);
    }
    public void complete(VisitorEvent event, byte[] frame) {
        RecognitionResult result = provider.recognize(event.getDeviceId(), frame);
        event.complete(result.matched() ? EventType.RECOGNIZED : EventType.UNKNOWN,
                result.matched() ? result.name() : null, result.matched() && result.authorized(), null);
        repository.save(event);
        String type = result.matched() ? "VISITOR_RECOGNIZED" : "VISITOR_UNKNOWN";
        String message = result.matched() ? result.name() + " is at the door" : "Unrecognized visitor at the door";
        messaging.convertAndSend("/topic/notify", new com.aditya.rtos_doorbell.dto.Notification(type, message));
    }
}
