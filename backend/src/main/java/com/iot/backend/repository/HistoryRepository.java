package com.iot.backend.repository;

import com.iot.backend.entity.History;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.List;
import java.time.LocalDateTime;

@Repository
public interface HistoryRepository extends JpaRepository<History, Integer> {
    String HISTORY_FILTER = """
            WHERE (:deviceCode IS NULL OR d.code = :deviceCode)
              AND (:action IS NULL OR h.action = :action)
              AND (:commandStatus IS NULL OR h.status = :commandStatus)
              AND (:pattern IS NULL
                OR LOWER(COALESCE(u.username, 'Hệ thống (Auto)')) LIKE :pattern ESCAPE '!'
                OR CAST(FUNCTION('DATE_FORMAT', h.createdAt, '%Y-%m-%d %H:%i:%s') AS string)
                    LIKE :pattern ESCAPE '!')
            """;

    // LEFT JOIN keeps automatic commands; only the data query fetches the to-one relations.
    @Query(value = "SELECT h FROM History h JOIN FETCH h.device d LEFT JOIN FETCH h.user u " + HISTORY_FILTER,
            countQuery = "SELECT COUNT(h) FROM History h JOIN h.device d LEFT JOIN h.user u " + HISTORY_FILTER)
    Page<History> searchHistory(
            @Param("deviceCode") String deviceCode,
            @Param("action") String action,
            @Param("commandStatus") String commandStatus,
            @Param("pattern") String pattern, Pageable pageable);

    boolean existsByDeviceIdAndStatus(Integer deviceId, String status);

    Optional<History> findByIdAndDeviceId(Integer id, Integer deviceId);

    Optional<History> findTopByDeviceIdAndStatusReceivedIsNotNullAndStatusInOrderByCreatedAtDescIdDesc(
            Integer deviceId, Collection<String> statuses);

    @Query("SELECT DISTINCT h.device.code FROM History h WHERE h.status = 'pending' AND h.createdAt <= :cutoff")
    List<String> findExpiredPendingDeviceCodes(@Param("cutoff") LocalDateTime cutoff);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE History h 
            SET h.status = 'failed' 
            WHERE h.device.id = :deviceId
            AND h.status = 'pending' 
            AND h.createdAt <= :cutoff
    """)
    int failExpiredPending(@Param("deviceId") Integer deviceId, @Param("cutoff") LocalDateTime cutoff);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE History h 
            SET h.status = 'failed' 
            WHERE h.id = :id 
            AND h.device.id = :deviceId 
            AND h.status = 'pending'""")
    int failPending(@Param("id") Integer id, @Param("deviceId") Integer deviceId);

}
