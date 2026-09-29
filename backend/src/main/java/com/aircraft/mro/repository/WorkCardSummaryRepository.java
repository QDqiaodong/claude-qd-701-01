package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WorkCardSummary;

/**
 * 【只读】汇总视图仓库。
 *
 * <p>映射的是 {@code @Subselect} 实体（数据库里没有表），因此这里只有查询方法，
 * 全部走派生查询 —— 没有任何 save/delete，也没有 Specification / nativeQuery。
 */
public interface WorkCardSummaryRepository extends JpaRepository<WorkCardSummary, Long> {

    /** 全量汇总：飞机列表页一次性捞出来按 aircraftId 建索引 */
    List<WorkCardSummary> findAll();

    /** 按累计工时倒序：谁占用机库工时最多 */
    List<WorkCardSummary> findAllByOrderByTotalHourDesc();

    /** 未闭环工卡最多的飞机 */
    List<WorkCardSummary> findAllByOpenCardGreaterThanOrderByOpenCardDesc(long openCard);

    Optional<WorkCardSummary> findById(Long id);
}
