package com.aditya.rtos_doorbell.service;

public record RecognitionResult(String name, boolean authorized) {
    public boolean matched() { return name != null && !name.isBlank(); }
}
