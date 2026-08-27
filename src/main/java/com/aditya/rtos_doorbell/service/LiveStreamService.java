package com.aditya.rtos_doorbell.service;

import com.aditya.rtos_doorbell.stream.FrameBuffer;
import org.springframework.stereotype.Service;
import java.io.*;

@Service
public class LiveStreamService {
    private final FrameBuffer frames;
    public LiveStreamService(FrameBuffer frames) { this.frames = frames; }
    public void stream(String deviceId, OutputStream output) throws IOException {
        while (!Thread.currentThread().isInterrupted()) {
            byte[] frame = frames.get(deviceId);
            if (frame != null) {
                output.write(("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n").getBytes());
                output.write(frame); output.write("\r\n".getBytes()); output.flush();
            }
            try { Thread.sleep(67); } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); break;
            }
        }
    }
}
