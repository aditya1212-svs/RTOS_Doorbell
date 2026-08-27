package com.aditya.rtos_doorbell.controller;

import com.aditya.rtos_doorbell.dto.DailyDigestResponse;
import com.aditya.rtos_doorbell.service.DigestService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/summary")
public class SummaryController {
    private final DigestService service;
    public SummaryController(DigestService service) { this.service = service; }
    @GetMapping("/{date}")
    public DailyDigestResponse get(@PathVariable LocalDate date) {
        var digest = service.find(date);
        return new DailyDigestResponse(digest.getDate(), digest.getSummary());
    }
}
