package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.Aircraft;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {

    List<Aircraft> findAllByOrderByTailNoAsc();

    Optional<Aircraft> findByTailNo(String tailNo);

    boolean existsByTailNo(String tailNo);

    List<Aircraft> findAllByModelOrderByTailNoAsc(String model);

    long countByAirworthy(String airworthy);

    /**
     * 适航指令重算取飞机事实时加行级悲观写锁：飞行登记 / 工卡变化 / 批量重算并发时，
     * 后到的事务在同一行上排队，拿到锁后一定读到的是最新累计值与最新工卡状态，
     * 从根上杜绝“较旧的计算结果覆盖新结果”。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select a from Aircraft a where a.id = :id")
    Optional<Aircraft> findByIdForUpdate(Long id);
}
