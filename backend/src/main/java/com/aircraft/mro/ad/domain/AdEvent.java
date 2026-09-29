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
 * 适航指令事件流（append-only）：创建/修订、范围与阈值变化、重算、
 * 状态流转、落实、工卡关联与失效、证据增减、批量任务进展全部在这里留痕。
 */
@Data
@Entity
@Table(name = "ad_event")
public class AdEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "directive_id", nullable = false)
    private Long directiveId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    /** 机队级事件（发布/修订/批量）可为 null */
    @Column(name = "aircraft_id")
    private Long aircraftId;

    /** CREATE / REVISE / SCOPE_CHANGE / THRESHOLD_CHANGE / RECOMPUTE / STATUS_CHANGE /
     *  COMPLY / CARD_LINK / CARD_UNLINK / CARD_CHANGED / EVIDENCE_ADD / EVIDENCE_MISSING / BATCH_… */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "detail", length = 1024)
    private String detail;

    @Column(name = "batch_job_id")
    private Long batchJobId;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
