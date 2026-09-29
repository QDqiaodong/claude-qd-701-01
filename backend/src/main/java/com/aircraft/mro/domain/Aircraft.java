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

/** 飞机台账。 */
@Data
@Entity
@Table(name = "aircraft")
public class Aircraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 机号（注册号），如 B-6032 */
    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    /** 机型：B737 / A320 / A330 / ARJ21 */
    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "airline", nullable = false, length = 64)
    private String airline;

    /** 总飞行小时；NULL 表示累计值尚未录入（适航指令判待核实） */
    @Column(name = "total_fh", precision = 12, scale = 2)
    private BigDecimal totalFh;

    /** 总起落次数；NULL 表示累计值尚未录入（适航指令判待核实） */
    @Column(name = "total_cycles")
    private Integer totalCycles;

    /** AIRWORTHY 适航 / GROUNDED 停场待修 / INSPECTION 定检中 / STORED 封存 */
    @Column(name = "airworthy", nullable = false, length = 16)
    private String airworthy;

    @Column(name = "note", length = 128)
    private String note;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
