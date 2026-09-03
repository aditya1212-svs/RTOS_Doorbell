package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.dto.DailyDigestResponse;
import com.aditya.rtos_doorbell.entity.DailyDigest;
import com.aditya.rtos_doorbell.entity.EventType;
import com.aditya.rtos_doorbell.entity.VisitorEvent;
import com.aditya.rtos_doorbell.repository.DailyDigestRepository;
import com.aditya.rtos_doorbell.repository.VisitorEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies (TASK-007) that DigestService:
 *  - scopes the query to the full day in Asia/Kolkata,
 *  - groups RING + recognition events into interactions,
 *  - persists a DailyDigest and pushes a DailyDigestResponse to /topic/digest.
 */
class DigestServiceTest {

    private final VisitorEventRepository events = mock(VisitorEventRepository.class);
    private final DailyDigestRepository digests = mock(DailyDigestRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

    private DigestService service() {
        return new DigestService(events, digests, new PlainTextDigestFormatter(), messaging, "Asia/Kolkata");
    }

    @Test
    void generateQueriesTheWholeAsiaKolkataDay() {
        // 2026-08-27 00:00 +05:30 == 2026-08-26 18:30Z
        // 2026-08-28 00:00 +05:30 == 2026-08-27 18:30Z
        when(events.findByTimestampGreaterThanEqualAndTimestampLessThan(any(), any())).thenReturn(List.of());
        when(digests.findByDate(LocalDate.of(2026, 8, 27))).thenReturn(Optional.empty());
        when(digests.save(any(DailyDigest.class))).thenAnswer(inv -> inv.getArgument(0));

        service().generate(LocalDate.of(2026, 8, 27));

        verify(events).findByTimestampGreaterThanEqualAndTimestampLessThan(
                Instant.parse("2026-08-26T18:30:00Z"),
                Instant.parse("2026-08-27T18:30:00Z"));
    }

    @Test
    void generatePersistsSummaryAndPublishesToTopicDigest() {
        when(digests.findByDate(LocalDate.of(2026, 8, 27))).thenReturn(Optional.empty());
        when(digests.save(any(DailyDigest.class))).thenAnswer(inv -> inv.getArgument(0));

        // A RING at 10:00 IST, recognized 30s later as Alice; an unknown visitor.
        when(events.findByTimestampGreaterThanEqualAndTimestampLessThan(any(), any())).thenReturn(List.of(
                event(Instant.parse("2026-08-27T04:30:00Z"), EventType.RING),
                event(Instant.parse("2026-08-27T04:30:30Z"), EventType.RECOGNIZED, "Alice"),
                event(Instant.parse("2026-08-27T05:00:00Z"), EventType.UNKNOWN),
                event(Instant.parse("2026-08-27T06:00:00Z"), EventType.UNLOCK_GRANTED)));

        DailyDigest result = service().generate(LocalDate.of(2026, 8, 27));

        // 2 interactions (Alice recognized + unknown), 1 unlock.
        String expected = "Today there were 2 visitor interactions."
                + " Alice visited 1 times."
                + " There were 1 unrecognized visitors."
                + " The door was unlocked 1 times.";
        assertEquals(expected, result.getSummary());

        verify(digests).save(argThat(d -> LocalDate.of(2026, 8, 27).equals(d.getDate())));
        verify(messaging).convertAndSend(eq("/topic/digest"),
                argThat((DailyDigestResponse r) ->
                        LocalDate.of(2026, 8, 27).equals(r.date()) && expected.equals(r.summary())));
    }

    @Test
    void digestRunsAt22hInAsiaKolkata() throws Exception {
        var method = DigestService.class.getDeclaredMethod("generatePreviousDay");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertEquals("${app.digest.cron:0 0 22 * * *}", scheduled.cron());
        assertEquals("${app.timezone:Asia/Kolkata}", scheduled.zone());
    }

    private VisitorEvent event(Instant at, EventType type) {
        VisitorEvent e = new VisitorEvent(at, "door-1", type);
        e.complete(type, type == EventType.RECOGNIZED ? "Alice" : null, false, null);
        return e;
    }

    private VisitorEvent event(Instant at, EventType type, String name) {
        VisitorEvent e = new VisitorEvent(at, "door-1", type);
        e.complete(type, name, false, null);
        return e;
    }
}