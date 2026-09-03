package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.DeviceEventRequest;
import com.aditya.rtos_doorbell.dto.VisitorEventResponse;
import com.aditya.rtos_doorbell.entity.*;
import com.aditya.rtos_doorbell.repository.VisitorEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

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

    @Transactional(readOnly = true)
    public List<VisitorEventResponse> list(int limit) {
        int pageSize = Math.min(Math.max(limit, 1), 100);
        return repository.findAllByOrderByTimestampDesc(PageRequest.of(0, pageSize)).stream()
                .map(event -> new VisitorEventResponse(event.getId(), event.getTimestamp(), event.getDeviceId(),
                        event.getType(), event.getRecognizedName(), event.isAuthorized(), event.getFrameUrl()))
                .toList();
    }
    public void recognize(VisitorEvent event, byte[] frame) {
        recognitionService.complete(event, frame);
    }
    public void notifyRecognition(VisitorEvent event) {
        recognitionService.notifyCompleted(event);
    }
}
