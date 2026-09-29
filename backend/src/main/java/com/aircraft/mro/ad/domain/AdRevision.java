package com.aircraft.mro.ad.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 适航指令修订版：同一 {@code directiveId} 下多个版本并存。
 *
 * <p>三类截止阈值（日历日期 / 累计飞行小时 / 累计起落次数）可任意组合，
 * 任一阈值先到即按最早限制执行；临期窗口逐维分别配置。
 * 阈值为 null 表示该维度不设阈值；配置矛盾写入 {@link #configIssue}，
 * 命中范围的飞机一律进入待核实，不允许被误判为不适用或已落实。
 */
@Data
@Entity
@Table(name = "ad_revision")
public class AdRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "directive_id", nullable = false)
    private Long directiveId;

    /** 修订版号：0 / 1 / 2…（同一指令内唯一） */
    @Column(name = "revision_no", nullable = false, length = 16)
    private String revisionNo;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** ACTIVE 当前生效 / SUPERSEDED 被替代（历史冻结，不再参与重算） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_fh", precision = 12, scale = 2)
    private BigDecimal dueFh;

    @Column(name = "due_cycles")
    private Integer dueCycles;

    /** 临期提前天数（仅设了日期阈值时生效） */
    @Column(name = "warn_days")
    private Integer warnDays;

    @Column(name = "warn_fh", precision = 12, scale = 2)
    private BigDecimal warnFh;

    @Column(name = "warn_cycles")
    private Integer warnCycles;

    /** 配置矛盾说明；非空时命中范围的飞机全部判为待核实 */
    @Column(name = "config_issue", length = 255)
    private String configIssue;

    /** 【仅验收演示】首次批量重算时对这些机号强制失败，逗号分隔；重试不再失败 */
    @Column(name = "demo_fail_tails", length = 255)
    private String demoFailTails;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
