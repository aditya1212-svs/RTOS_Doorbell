package com.aditya.rtos_doorbell.dto;

import jakarta.validation.constraints.NotBlank;

public record PersonCreateRequest(@NotBlank String name) {}
