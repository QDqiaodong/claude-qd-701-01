package com.aircraft.mro.domain;

import java.math.BigDecimal;
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
 * 适航指令落实清单：同一修订版 + 同一架飞机只有一行当前结果（UNIQUE 兜底）。
 *
 * <p>修订版被取代后，这一行属于旧版历史，保留不重算；新版本会为同一架飞机生成新行。
 */
@Data
@Entity
@Table(name = "ad_compliance")
public class AdCompliance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** NOT_DUE / NEAR_DUE / OVERDUE / COMPLIED / PENDING / NOT_APPLICABLE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "applicable", nullable = false)
    private Integer applicable;

    /** 待核实原因（分号分隔） */
    @Column(name = "pending_reasons", length = 512)
    private String pendingReasons;

    /** 最早到限维度：DATE / FH / CYCLE */
    @Column(name = "limiting_dim", length = 8)
    private String limitingDim;

    @Column(name = "days_to_due")
    private Integer daysToDue;

    @Column(name = "fh_remaining", precision = 12, scale = 2)
    private BigDecimal fhRemaining;

    @Column(name = "cycle_remaining")
    private Integer cycleRemaining;

    /** 本次计算所依据的数据版本 tick：旧 tick 的结果不得覆盖新 tick */
    @Column(name = "calc_tick", nullable = false)
    private Long calcTick;

    @Column(name = "calc_at")
    private LocalDateTime calcAt;

    @Column(name = "complied_at")
    private LocalDateTime compliedAt;

    @Column(name = "complied_by", length = 32)
    private String compliedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 乐观锁：落实/作废与并发重算同时发生时只有一个能写入 */
    @Version
    private Long version;
}
