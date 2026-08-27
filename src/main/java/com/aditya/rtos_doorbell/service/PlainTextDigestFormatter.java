package com.aditya.rtos_doorbell.service;

import org.springframework.stereotype.Component;
import java.util.stream.Collectors;

@Component
public class PlainTextDigestFormatter implements DigestFormatter {
    @Override public String format(DigestData data) {
        String people = data.recognized().entrySet().stream()
                .map(e -> e.getKey() + " visited " + e.getValue() + " times.")
                .collect(Collectors.joining(" "));
        StringBuilder result = new StringBuilder("Today there were ")
                .append(data.interactions()).append(" visitor interactions.");
        if (!people.isBlank()) result.append(" ").append(people);
        if (data.unknown() > 0) result.append(" There were ").append(data.unknown()).append(" unrecognized visitors.");
        if (data.unlocks() > 0) result.append(" The door was unlocked ").append(data.unlocks()).append(" times.");
        return result.toString();
    }
}
