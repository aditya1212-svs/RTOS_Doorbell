package com.aditya.rtos_doorbell.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the plain-text digest output (part of TASK-007): the summary the
 * doorbell pushes to /topic/digest must be a human-readable plain-text line.
 */
class PlainTextDigestFormatterTest {

    private final PlainTextDigestFormatter formatter = new PlainTextDigestFormatter();

    @Test
    void emptyDayProducesBaselineSentence() {
        String out = formatter.format(new DigestData(0, Map.of(), 0, 0, 0, 0));
        assertEquals("Today there were 0 visitor interactions.", out);
    }

    @Test
    void recognizedVisitorsAreListedWithCountsInSortedOrder() {
        TreeMap<String, Long> recognized = new TreeMap<>();
        recognized.put("Alice", 2L);
        recognized.put("Bob", 1L);
        String out = formatter.format(new DigestData(3, recognized, 0, 0, 0, 0));

        assertTrue(out.contains("Alice visited 2 times."));
        assertTrue(out.contains("Bob visited 1 times."));
        // Sorted lexicographically (Alice before Bob), matching the TreeMap ordering.
        assertTrue(out.indexOf("Alice") < out.indexOf("Bob"));
    }

    @Test
    void unknownUnlocksAndMotionsAreIncludedWhenPresent() {
        String out = formatter.format(new DigestData(2, Map.of("Alice", 1L), 1, 1, 0, 0));
        assertTrue(out.contains("There were 1 unrecognized visitors."));
        assertTrue(out.contains("The door was unlocked 1 times."));
    }

    @Test
    void absentCountsAreNotMentioned() {
        String out = formatter.format(new DigestData(1, Map.of("Alice", 1L), 0, 0, 0, 2));
        assertFalse(out.contains("unrecognized"));
        assertFalse(out.contains("unlocked"));
    }
}