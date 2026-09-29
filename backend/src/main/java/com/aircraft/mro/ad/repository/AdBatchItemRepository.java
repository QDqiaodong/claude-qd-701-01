package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.aircraft.mro.ad.domain.AdBatchItem;

public interface AdBatchItemRepository extends JpaRepository<AdBatchItem, Long> {

    List<AdBatchItem> findByJobIdOrderByIdAsc(Long jobId);

    List<AdBatchItem> findByJobIdAndStatusOrderByIdAsc(Long jobId, String status);

    /**
     * 明细级原子计数更新：每个飞机的重算在自己的短事务里提交成功/失败，
     * 不与其它飞机的处理互锁，普通查询与飞行登记不会被大批量任务长时间占住。
     */
    @Modifying
    @Query("update AdBatchItem i set i.status = :status, i.failReason = :failReason, "
            + "i.attempt = i.attempt + 1, i.updatedAt = :now where i.id = :id")
    void markResult(Long id, String status, String failReason, java.time.LocalDateTime now);
}
