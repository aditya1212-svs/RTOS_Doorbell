package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.Notification;
import com.aditya.rtos_doorbell.dto.StoredFaceResponse;
import com.aditya.rtos_doorbell.dto.FaceBoundingBox;
import com.aditya.rtos_doorbell.entity.EventType;
import com.aditya.rtos_doorbell.entity.VisitorEvent;
import com.aditya.rtos_doorbell.repository.VisitorEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class RecognitionService {
    private static final Logger log = LoggerFactory.getLogger(RecognitionService.class);
    private static final long PENDING_EVENT_MAX_AGE_SECONDS = 300;

    private final VisitorEventRepository repository;
    private final FaceRecognitionProvider provider;
    private final ConcurrentHashMap<String, Queue<VisitorEvent>> pending = new ConcurrentHashMap<>();
    private final java.util.Set<String> notifiedEvents = ConcurrentHashMap.newKeySet();
    private final SimpMessagingTemplate messaging;
    private final Executor executor;
    private final StoredFaceService storedFaceService;
    private static final long DETECTION_EVENT_COOLDOWN_MILLIS = 5_000;
    private final ConcurrentHashMap<String, Long> lastDetectionEventAt = new ConcurrentHashMap<>();

    @Autowired
    public RecognitionService(VisitorEventRepository repository, FaceRecognitionProvider provider,
                              SimpMessagingTemplate messaging,
                              @Qualifier("faceDetectionExecutor") Executor executor,
                              StoredFaceService storedFaceService) {
        this.repository = repository;
        this.provider = provider;
        this.messaging = messaging;
        this.executor = executor;
        this.storedFaceService = storedFaceService;
    }

    /** Kept for small integrations/tests that used the original constructor. */
    public RecognitionService(VisitorEventRepository repository, FaceRecognitionProvider provider,
                              SimpMessagingTemplate messaging) {
        this(repository, provider, messaging, Runnable::run, null);
    }

    public void markPending(VisitorEvent event) {
        if (event == null || event.getType() != EventType.RING) return;
        Queue<VisitorEvent> events = pending.computeIfAbsent(event.getDeviceId(), ignored -> new ConcurrentLinkedQueue<>());
        Instant cutoff = Instant.now().minusSeconds(PENDING_EVENT_MAX_AGE_SECONDS);
        events.removeIf(item -> item.getTimestamp().isBefore(cutoff));
        events.add(event);
    }

    /**
     * Starts recognition for a live detector result. The simulator samples
     * continuously, so one short device-scoped cooldown prevents duplicate
     * events while preserving automatic detection.
     */
    public void processDetectedPerson(String deviceId, byte[] frame) {
        if (deviceId == null || deviceId.isBlank() || frame == null || frame.length == 0) return;
        long now = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicBoolean accepted = new java.util.concurrent.atomic.AtomicBoolean();
        lastDetectionEventAt.compute(deviceId, (key, previous) -> {
            if (previous == null || now - previous >= DETECTION_EVENT_COOLDOWN_MILLIS) {
                accepted.set(true);
                return now;
            }
            return previous;
        });
        if (!accepted.get()) return;

        VisitorEvent event = repository.save(new VisitorEvent(Instant.now(), deviceId, EventType.RING));
        markPending(event);
        processLatest(deviceId, frame);
    }

    public void processLatest(String deviceId, byte[] frame) {
        Queue<VisitorEvent> events = pending.get(deviceId);
        VisitorEvent event;
        Instant cutoff = Instant.now().minusSeconds(PENDING_EVENT_MAX_AGE_SECONDS);
        do {
            event = events == null ? null : events.poll();
        } while (event != null && event.getTimestamp().isBefore(cutoff));
        if (event == null) return;
        VisitorEvent pendingEvent = event;
        try {
            CompletableFuture.runAsync(() -> complete(pendingEvent, frame), executor);
        } catch (TaskRejectedException e) {
            log.warn("Recognition queue is full for device {}", deviceId);
            completeAsUnknown(pendingEvent, frame);
        }
    }

    public void complete(VisitorEvent event, byte[] frame) {
        if (event == null) return;
        synchronized (event) {
            // processLatest consumes the pending event, and this guard also makes
            // direct/retried completion idempotent for one RING interaction.
            if (event.getType() != EventType.RING) return;

            RecognitionResult result;
            try {
                result = provider.recognize(event.getDeviceId(), frame);
                if (result == null) result = unknownResult();
            } catch (FaceRecognitionException e) {
                // Do not leave a RING interaction pending when the model is unavailable.
                log.warn("Face recognition failed for device {}: {}", event.getDeviceId(), e.getMessage());
                result = unknownResult();
            } catch (RuntimeException e) {
                log.warn("Unexpected face recognition failure for device {}", event.getDeviceId(), e);
                result = unknownResult();
            }

            List<FaceBoundingBox> faces = result.faces() == null ? List.of() : result.faces();
            String frameUrl = null;
            if (!result.matched() && storedFaceService != null && !faces.isEmpty()) {
                try {
                    List<StoredFaceResponse> stored = storedFaceService.store(event.getDeviceId(), frame,
                            "image/jpeg", faces);
                    if (!stored.isEmpty()) frameUrl = stored.get(0).imageUrl();
                } catch (RuntimeException e) {
                    log.warn("Unable to store unknown visitor face for {}", event.getDeviceId(), e);
                }
            }

            event.complete(result.matched() ? EventType.RECOGNIZED : EventType.UNKNOWN,
                    result.matched() ? result.name() : null,
                    result.matched() && result.authorized(), frameUrl);
            repository.save(event);
            publishNotification(event);
        }
    }

    private RecognitionResult unknownResult() {
        return new RecognitionResult(null, false, 0.0, List.of());
    }

    /** Publishes at most one visitor notification for a completed event. */
    public void notifyCompleted(VisitorEvent event) {
        if (event == null || (event.getType() != EventType.RECOGNIZED && event.getType() != EventType.UNKNOWN)) return;
        synchronized (event) {
            if (!notifiedEvents.add(eventKey(event))) return;
            boolean matched = event.getType() == EventType.RECOGNIZED;
            String type = matched ? "VISITOR_RECOGNIZED" : "VISITOR_UNKNOWN";
            String message = matched ? event.getRecognizedName() + " is at the door"
                    : "Unknown person is at the door";
            messaging.convertAndSend("/topic/notify", new Notification(type, message,
                    matched ? event.getRecognizedName() : null, event.getTimestamp(), event.getId()));
        }
    }

    private void publishNotification(VisitorEvent event) {
        notifyCompleted(event);
    }

    private String eventKey(VisitorEvent event) {
        return event.getId() == null ? "object:" + System.identityHashCode(event) : "id:" + event.getId();
    }

    private void completeAsUnknown(VisitorEvent event, byte[] frame) {
        try {
            complete(event, frame);
        } catch (RuntimeException e) {
            log.warn("Unable to complete recognition fallback for {}", event.getDeviceId(), e);
        }
    }
}
