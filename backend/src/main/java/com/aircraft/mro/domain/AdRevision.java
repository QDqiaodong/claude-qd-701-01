package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 适航指令修订版（不可变历史）。
 *
 * <p>生效（EFFECTIVE）后不再修改；适用范围或阈值变化时创建新的修订版，
 * 旧版转为 SUPERSEDED 并冻结其全部清单判断与证据。
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

    /** R0 原版 / R1 第 1 次修订 … */
    @Column(name = "revision_no", nullable = false, length = 16)
    private String revisionNo;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** DRAFT 草稿 / EFFECTIVE 生效 / SUPERSEDED 已被取代（只读冻结） */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    /** 适用机型，分号分隔，如 B737;A320 */
    @Column(name = "applicable_models", nullable = false, length = 128)
    private String applicableModels;

    /** 机号范围下界（按机号数字部分比较），NULL=不限 */
    @Column(name = "tail_range_from", length = 16)
    private String tailRangeFrom;

    /** 机号范围上界，NULL=不限；只填一边视为边界不完整 → 待核实 */
    @Column(name = "tail_range_to", length = 16)
    private String tailRangeTo;

    // ---- 三类截止阈值：NULL 表示该维度不限制 ----
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "due_fh", precision = 12, scale = 2)
    private BigDecimal dueFh;

    @Column(name = "due_cycles")
    private Integer dueCycles;

    // ---- 三类临期预警提前量 ----
    @Column(name = "warn_days", nullable = false)
    private Integer warnDays;

    @Column(name = "warn_fh", nullable = false, precision = 12, scale = 2)
    private BigDecimal warnFh;

    @Column(name = "warn_cycles", nullable = false)
    private Integer warnCycles;

    @Column(name = "revision_note", length = 255)
    private String revisionNote;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
