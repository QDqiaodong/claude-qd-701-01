package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 批量重算作业逐机明细：失败原因与重试次数持久化，续跑只处理 PENDING/FAILED 项。 */
@Data
@Entity
@Table(name = "ad_recalc_job_item")
public class AdRecalcJobItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** PENDING / SUCCESS / FAILED / NOT_APPLICABLE */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
