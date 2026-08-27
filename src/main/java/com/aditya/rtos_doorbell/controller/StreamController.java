package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.service.LiveStreamService;
import com.aditya.rtos_doorbell.stream.FrameBuffer;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class StreamController {
    private final FrameBuffer buffer; private final LiveStreamService stream;
    public StreamController(FrameBuffer buffer, LiveStreamService stream) { this.buffer = buffer; this.stream = stream; }
    @GetMapping(value = "/stream/live/{deviceId}", produces = "multipart/x-mixed-replace;boundary=frame")
    public ResponseEntity<StreamingResponseBody> live(@PathVariable String deviceId) {
        if (!buffer.contains(deviceId)) return ResponseEntity.notFound().build();
        StreamingResponseBody body = output -> stream.stream(deviceId, output);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(
                "multipart/x-mixed-replace;boundary=frame")).body(body);
    }
}
