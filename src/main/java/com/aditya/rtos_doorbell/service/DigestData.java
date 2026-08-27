package com.aditya.rtos_doorbell.service;

import java.util.Map;

public record DigestData(int interactions, Map<String, Long> recognized, long unknown,
                         long unlocks, long rings, long motions) {}
