package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.ReleaseOrder;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ReleaseOrderRepository extends JpaRepository<ReleaseOrder, Long> {

    List<ReleaseOrder> findAllByOrderByCreatedAtDesc();

    List<ReleaseOrder> findAllByAircraftIdOrderByCreatedAtDesc(Long aircraftId);

    Optional<ReleaseOrder> findByReleaseNo(String releaseNo);

    /** 该机当前占位（草稿/待审/已放行未出位）的有效审签，最多一条（UNIQUE 兜底） */
    List<ReleaseOrder> findAllByActiveAircraftId(Long activeAircraftId);

    long countByStatus(String status);

    /**
     * 关键流转动作（提交/机械放行/终审/驳回/出位）一律走行级悲观锁串行化。
     * 两人同时终审：第一个事务提交 RELEASED 并释放锁，第二个拿到锁后读到的已是新状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select r from ReleaseOrder r where r.id = :id")
    Optional<ReleaseOrder> findByIdForUpdate(Long id);
}
