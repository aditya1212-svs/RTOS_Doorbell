package com.aditya.rtos_doorbell.repository;

import com.aditya.rtos_doorbell.entity.StoredFace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface StoredFaceRepository extends JpaRepository<StoredFace, UUID> {
    List<StoredFace> findByDeviceIdOrderByDetectedAtDesc(String deviceId, Pageable pageable);
}
