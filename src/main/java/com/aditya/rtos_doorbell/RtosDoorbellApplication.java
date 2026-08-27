package com.aditya.rtos_doorbell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RtosDoorbellApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtosDoorbellApplication.class, args);
    }

}
