package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 适航指令审计事件（append-only：状态变化、证据、关联、重算作业都在此留痕）。 */
@Data
@Entity
@Table(name = "ad_event")
public class AdEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "directive_id", nullable = false)
    private Long directiveId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "compliance_id")
    private Long complianceId;

    @Column(name = "aircraft_id")
    private Long aircraftId;

    /** REVISION_PUBLISH / RECALC / STATUS_CHANGE / COMPLY / LINK / EVIDENCE / EVIDENCE_VOID / RECALC_JOB */
    @Column(name = "event_type", nullable = false, length = 24)
    private String eventType;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "detail", nullable = false, length = 512)
    private String detail;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
