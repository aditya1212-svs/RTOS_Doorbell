package com.aditya.rtos_doorbell.stream;

import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FrameBuffer {
    private final ConcurrentHashMap<String, byte[]> latest = new ConcurrentHashMap<>();

    public void put(String deviceId, byte[] frame) {
        if (frame == null || frame.length == 0) return;
        latest.put(deviceId, frame.clone());
    }
    public byte[] get(String deviceId) {
        byte[] frame = latest.get(deviceId);
        return frame == null ? null : frame.clone();
    }
    public boolean contains(String deviceId) { return latest.containsKey(deviceId); }
}
