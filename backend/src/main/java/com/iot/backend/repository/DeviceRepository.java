package com.iot.backend.repository;

import com.iot.backend.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Integer> {
    // Serialize reservation/ACK/timeout per device across concurrent requests and app instances.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Device d WHERE d.code = :code")
    Optional<Device> findByCodeForUpdate(@Param("code") String code);

    Optional<Device> findByCode(String code);

    boolean existsByCode(String code);
}
