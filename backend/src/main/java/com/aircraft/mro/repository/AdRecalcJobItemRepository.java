package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.AdRecalcJobItem;

public interface AdRecalcJobItemRepository extends JpaRepository<AdRecalcJobItem, Long> {

    List<AdRecalcJobItem> findAllByJobIdOrderByIdAsc(Long jobId);

    List<AdRecalcJobItem> findAllByJobIdAndStatus(Long jobId, String status);

    /** worker 领取下一个待处理项（单 worker 串行；续跑时 PENDING 优先，FAILED 其次） */
    Optional<AdRecalcJobItem> findFirstByJobIdAndStatusOrderByIdAsc(Long jobId, String status);
}
