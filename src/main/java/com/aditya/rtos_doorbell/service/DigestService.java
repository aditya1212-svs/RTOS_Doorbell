package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.entity.*;
import com.aditya.rtos_doorbell.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DigestService {
    private final VisitorEventRepository events;
    private final DailyDigestRepository digests;
    private final DigestFormatter formatter;
    private final SimpMessagingTemplate messaging;
    private final ZoneId zone;

    public DigestService(VisitorEventRepository events, DailyDigestRepository digests, DigestFormatter formatter,
                         SimpMessagingTemplate messaging, @Value("${app.timezone:Asia/Kolkata}") String timezone) {
        this.events = events; this.digests = digests; this.formatter = formatter;
        this.messaging = messaging; this.zone = ZoneId.of(timezone);
    }
    public DailyDigest generate(LocalDate date) {
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<VisitorEvent> all = events.findByTimestampGreaterThanEqualAndTimestampLessThan(start, end);
        List<VisitorEvent> interactions = groupInteractions(all);
        Map<String, Long> recognized = interactions.stream().filter(e -> e.getType() == EventType.RECOGNIZED)
                .map(VisitorEvent::getRecognizedName).filter(Objects::nonNull)
                .collect(Collectors.groupingBy(Function.identity(), TreeMap::new, Collectors.counting()));
        long unknown = interactions.stream().filter(e -> e.getType() == EventType.UNKNOWN).count();
        DigestData data = new DigestData(interactions.size(), recognized, unknown,
                all.stream().filter(e -> e.getType() == EventType.UNLOCK_GRANTED).count(),
                all.stream().filter(e -> e.getType() == EventType.RING).count(),
                all.stream().filter(e -> e.getType() == EventType.MOTION).count());
        DailyDigest digest = digests.findByDate(date).orElseGet(() -> new DailyDigest(date, ""));
        digest.updateSummary(formatter.format(data));
        digest = digests.save(digest);
        messaging.convertAndSend("/topic/digest", new com.aditya.rtos_doorbell.dto.DailyDigestResponse(date, digest.getSummary()));
        return digest;
    }
    private List<VisitorEvent> groupInteractions(List<VisitorEvent> all) {
        List<VisitorEvent> candidates = all.stream().filter(e -> e.getType() == EventType.RING
                || e.getType() == EventType.RECOGNIZED || e.getType() == EventType.UNKNOWN)
                .sorted(Comparator.comparing(VisitorEvent::getDeviceId).thenComparing(VisitorEvent::getTimestamp)).toList();
        List<VisitorEvent> grouped = new ArrayList<>();
        for (VisitorEvent event : candidates) {
            VisitorEvent previous = grouped.isEmpty() ? null : grouped.get(grouped.size() - 1);
            if (previous == null || !previous.getDeviceId().equals(event.getDeviceId())
                    || event.getTimestamp().isAfter(previous.getTimestamp().plusSeconds(120))) {
                grouped.add(event);
            } else if (event.getType() == EventType.RECOGNIZED
                    || (event.getType() == EventType.UNKNOWN && previous.getType() == EventType.RING)) {
                grouped.set(grouped.size() - 1, event);
            }
        }
        return grouped;
    }
    @Scheduled(cron = "${app.digest.cron:0 0 22 * * *}", zone = "${app.timezone:Asia/Kolkata}")
    public void generatePreviousDay() { generate(LocalDate.now(zone).minusDays(1)); }
    public DailyDigest find(LocalDate date) {
        return digests.findByDate(date).orElseThrow(() -> new NoSuchElementException("No digest available for " + date));
    }
}
