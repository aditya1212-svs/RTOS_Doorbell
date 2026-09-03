package com.aditya.rtos_doorbell.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingCodecTest {
    @Test
    void roundTripsEmbeddingValues() {
        List<Double> original = List.of(-.25, 0.0, .75, 1.5);
        assertEquals(original, EmbeddingCodec.decode(EmbeddingCodec.encode(original), original.size()));
    }
}
