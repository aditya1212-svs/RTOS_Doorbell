package com.aditya.rtos_doorbell.dto;

import java.time.LocalDate;

public record DailyDigestResponse(LocalDate date, String summary) {}
