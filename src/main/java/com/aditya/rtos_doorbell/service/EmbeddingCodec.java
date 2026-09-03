package com.aditya.rtos_doorbell.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Converts model vectors to a stable, database-safe binary representation. */
public final class EmbeddingCodec {
    private EmbeddingCodec() {}

    public static byte[] encode(List<Double> values) {
        if (values == null || values.isEmpty()) {
            throw new FaceRecognitionException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Face model returned an empty embedding");
        }
        ByteBuffer buffer = ByteBuffer.allocate(values.size() * Double.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (Double value : values) {
            if (value == null || !Double.isFinite(value)) {
                throw new FaceRecognitionException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Face model returned an invalid embedding");
            }
            buffer.putDouble(value);
        }
        return buffer.array();
    }

    public static List<Double> decode(byte[] bytes, int dimensions) {
        if (bytes == null || dimensions <= 0 || bytes.length != dimensions * Double.BYTES) {
            throw new FaceRecognitionException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Stored face embedding is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        List<Double> values = new ArrayList<>(dimensions);
        for (int index = 0; index < dimensions; index++) {
            double value = buffer.getDouble();
            if (!Double.isFinite(value)) {
                throw new FaceRecognitionException(org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Stored face embedding is invalid");
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
