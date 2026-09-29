package com.aircraft.mro.domain;

import java.math.BigDecimal;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Subselect;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

/**
 * 只读汇总视图实体：按飞机聚合工卡数量与工时。
 *
 * <p>用 Hibernate 的 {@code @Subselect} 把一条 SELECT 直接映射成实体 —— 数据库里【没有】
 * 这张表或视图，Hibernate 每次查询都是拿 {@code @Subselect} 里的 SQL 去查。
 * 配 {@code @Immutable} 后它完全只读：save/delete 一律不允许，也没有脏检查开销。
 *
 * <p>{@code @Id} 用 aircraft_id：分组键天然唯一，正好当主键。
 * 它只服务于"飞机台账列表带出工卡汇总"这一个读场景。
 */
@Data
@Entity
@Immutable
@Subselect("SELECT w.aircraft_id AS id, "
        + "COUNT(*) AS total_card, "
        + "SUM(CASE WHEN w.status = 'CLOSED' THEN 1 ELSE 0 END) AS closed_card, "
        + "SUM(CASE WHEN w.status IN ('ASSIGNED', 'IN_PROGRESS', 'WAIT_INSP') THEN 1 ELSE 0 END) AS open_card, "
        + "SUM(w.actual_hour) AS total_hour "
        + "FROM work_card w "
        + "GROUP BY w.aircraft_id")
public class WorkCardSummary {

    /** 分组键 = 飞机 id */
    @Id
    private Long id;

    /** 工卡总数 */
    private Long totalCard;

    /** 已关闭工卡数 */
    private Long closedCard;

    /** 未闭环工卡数（待派工 + 进行中 + 待检验） */
    private Long openCard;

    /** 累计实际工时 */
    private BigDecimal totalHour;
}
