package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 定检工卡：待派工 → 进行中 → 待检验 → 已关闭，另可取消。 */
@Data
@Entity
@Table(name = "work_card")
public class WorkCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_no", nullable = false, length = 32)
    private String cardNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** A_CHECK A检 / C_CHECK C检 / ROUTINE 例行 / SPECIAL 专项 */
    @Column(name = "check_type", nullable = false, length = 16)
    private String checkType;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    @Column(name = "planned_hour", nullable = false, precision = 8, scale = 2)
    private BigDecimal plannedHour;

    @Column(name = "actual_hour", nullable = false, precision = 8, scale = 2)
    private BigDecimal actualHour;

    /** 可空：待派工阶段还没有开工时间 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 可空：未完工 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** ASSIGNED / IN_PROGRESS / WAIT_INSP / CLOSED / CANCELED */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "executor", length = 32)
    private String executor;

    @Column(name = "inspector", length = 32)
    private String inspector;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
