package com.iot.backend.repository;

import com.iot.backend.entity.DataSensor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface DataSensorRepository extends JpaRepository<DataSensor, Integer> {

    // Share the exact filters between the data query and the pagination count query.
    String HISTORY_FILTER = """
            WHERE (:sensorId IS NULL OR d.sensor.id = :sensorId)
              AND (:pattern IS NULL
                OR (:searchType <> 'time' AND CAST(d.value AS string) LIKE :pattern ESCAPE '!')
                OR (:searchType <> 'value' AND
                    CAST(FUNCTION('DATE_FORMAT', d.createdAt, '%Y-%m-%d %H:%i:%s') AS string)
                        LIKE :pattern ESCAPE '!')
                OR (:searchType = '' AND LOWER(s.name) LIKE :pattern ESCAPE '!'))
            """;


    /** Parameterized JPQL, including fetch for the data query only (no N+1 or fetch in COUNT). */
    @Query(value = "SELECT d FROM DataSensor d JOIN FETCH d.sensor s " + HISTORY_FILTER,
            countQuery = "SELECT COUNT(d) FROM DataSensor d JOIN d.sensor s " + HISTORY_FILTER)
    Page<DataSensor> searchHistory(@Param("sensorId") Integer sensorId,
                                   @Param("pattern") String pattern,
                                   @Param("searchType") String searchType, // "" | time | value
                                   Pageable pageable);


    /**
     * Lấy danh sách các mốc thời gian đo duy nhất gần nhất phục vụ đồ thị
     */
    @Query("SELECT DISTINCT d.createdAt FROM DataSensor d ORDER BY d.createdAt DESC")
    Page<LocalDateTime> findDistinctRecentCreatedAt(Pageable pageable);


    /** Chỉ lấy các lần đo đủ ba sensor; loại timestamp có bản ghi trùng để tránh ghép sai. */
    @Query("""
            SELECT d.createdAt FROM DataSensor d
            WHERE d.sensor.id IN (1, 2, 3)
            GROUP BY d.createdAt
            HAVING COUNT(d) = 3 AND COUNT(DISTINCT d.sensor.id) = 3
            ORDER BY d.createdAt DESC
            """)
    List<LocalDateTime> findCompleteMeasurementTimes(Pageable pageable);


    /**
     * Lấy danh sách các bản ghi tương ứng với tập hợp các mốc thời gian
     */
    @Query("SELECT d FROM DataSensor d JOIN FETCH d.sensor WHERE d.sensor.id IN (1, 2, 3) AND d.createdAt IN :timestamps ORDER BY d.createdAt ASC, d.id ASC")
    List<DataSensor> findByCreatedAtInOrderByCreatedAtAsc(@Param("timestamps") Collection<LocalDateTime> timestamps);


}
