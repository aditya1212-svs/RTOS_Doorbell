package com.aditya.rtos_doorbell.repository;

import com.aditya.rtos_doorbell.entity.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.*;

public interface VisitorEventRepository extends JpaRepository<VisitorEvent, Long> {
    List<VisitorEvent> findByTimestampGreaterThanEqualAndTimestampLessThan(Instant start, Instant end);
    List<VisitorEvent> findAllByOrderByTimestampDesc(Pageable pageable);
    Optional<VisitorEvent> findFirstByDeviceIdAndTypeOrderByTimestampDesc(String deviceId, EventType type);
}
