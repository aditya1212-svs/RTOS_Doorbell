package com.aditya.rtos_doorbell.repository;

import com.aditya.rtos_doorbell.entity.DailyDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface DailyDigestRepository extends JpaRepository<DailyDigest, Long> {
    Optional<DailyDigest> findByDate(LocalDate date);
}
