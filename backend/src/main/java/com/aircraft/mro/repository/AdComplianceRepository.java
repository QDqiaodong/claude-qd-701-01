package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.AdCompliance;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdComplianceRepository extends JpaRepository<AdCompliance, Long> {

    List<AdCompliance> findAllByRevisionId(Long revisionId);

    List<AdCompliance> findAllByAircraftIdOrderByUpdatedAtDesc(Long aircraftId);

    Optional<AdCompliance> findByRevisionIdAndAircraftId(Long revisionId, Long aircraftId);

    List<AdCompliance> findAllByStatus(String status);

    long countByStatus(String status);

    /**
     * 飞行登记 / 批量重算 / 工卡变化并发写同一行时，用行级悲观锁串行化。
     * 锁内重新读取飞机累计值、工卡与证据的最新状态，杜绝旧计算结果覆盖新结果。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select c from AdCompliance c where c.id = :id")
    Optional<AdCompliance> findByIdForUpdate(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select c from AdCompliance c where c.revisionId = :revisionId and c.aircraftId = :aircraftId")
    Optional<AdCompliance> findByRevisionAircraftForUpdate(Long revisionId, Long aircraftId);
}
