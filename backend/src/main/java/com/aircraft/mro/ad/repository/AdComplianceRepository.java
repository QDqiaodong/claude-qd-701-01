package com.aircraft.mro.ad.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.ad.domain.AdCompliance;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdComplianceRepository extends JpaRepository<AdCompliance, Long> {

    List<AdCompliance> findByRevisionId(Long revisionId);

    List<AdCompliance> findByAircraftId(Long aircraftId);

    Optional<AdCompliance> findByRevisionIdAndAircraftId(Long revisionId, Long aircraftId);

    /**
     * 重算写回时加行锁：飞机事实变化的重算与人工落实动作按同一把锁串行，
     * 锁内重新读取后再写，杜绝旧结果覆盖新结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select c from AdCompliance c where c.revisionId = :revisionId and c.aircraftId = :aircraftId")
    Optional<AdCompliance> findByRevisionIdAndAircraftIdForUpdate(Long revisionId, Long aircraftId);

    long countByStatus(String status);
}
