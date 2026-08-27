package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.service.RecognitionService;
import com.aditya.rtos_doorbell.stream.FrameBuffer;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/frame")
public class FrameController {
    private final FrameBuffer buffer; private final RecognitionService recognition;
    public FrameController(FrameBuffer buffer, RecognitionService recognition) {
        this.buffer = buffer; this.recognition = recognition;
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> upload(@RequestParam @NotBlank String deviceId,
                                       @RequestPart("frame") MultipartFile frame)
            throws IOException {
        byte[] bytes = frame.getBytes(); buffer.put(deviceId, bytes); recognition.processLatest(deviceId, bytes);
        return ResponseEntity.accepted().build();
    }
    @PostMapping(path = "/raw", consumes = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Void> uploadRaw(@RequestParam @NotBlank String deviceId, @RequestBody byte[] frame) {
        buffer.put(deviceId, frame); recognition.processLatest(deviceId, frame);
        return ResponseEntity.accepted().build();
    }
}
