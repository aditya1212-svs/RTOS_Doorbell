package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.DeviceEventRequest;
import com.aditya.rtos_doorbell.entity.*;
import com.aditya.rtos_doorbell.repository.VisitorEventRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import java.time.*;

@Service
public class VisitorEventService {
    private final VisitorEventRepository repository;
    private final SimpMessagingTemplate messaging;
    private final RecognitionService recognitionService;

    public VisitorEventService(VisitorEventRepository repository, SimpMessagingTemplate messaging,
                               RecognitionService recognitionService) {
        this.repository = repository; this.messaging = messaging; this.recognitionService = recognitionService;
    }
    public VisitorEvent accept(DeviceEventRequest request) {
        VisitorEvent event = repository.save(new VisitorEvent(request.timestamp(), request.deviceId(), request.type()));
        if (request.type() == EventType.RING) recognitionService.markPending(event);
        return event;
    }
    public void recognize(VisitorEvent event, byte[] frame) {
        recognitionService.complete(event, frame);
    }
    public void notifyRecognition(VisitorEvent event) {
        if (event.getType() == EventType.RECOGNIZED) {
            messaging.convertAndSend("/topic/notify",
                    new com.aditya.rtos_doorbell.dto.Notification("VISITOR_RECOGNIZED",
                            event.getRecognizedName() + " is at the door"));
        } else if (event.getType() == EventType.UNKNOWN) {
            messaging.convertAndSend("/topic/notify",
                    new com.aditya.rtos_doorbell.dto.Notification("VISITOR_UNKNOWN",
                            "Unrecognized visitor at the door"));
        }
    }
}
