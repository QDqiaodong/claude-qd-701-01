package com.aircraft.mro.ad.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 批量重算明细：一架飞机一行，失败原因固化在这里，可逐项重试。
 * 重试只更新本行，幂等的重算逻辑保证不会重复生成落实结果。
 */
@Data
@Entity
@Table(name = "ad_batch_item")
public class AdBatchItem {

    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempt", nullable = false)
    private Integer attempt;

    @Column(name = "fail_reason", length = 512)
    private String failReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
