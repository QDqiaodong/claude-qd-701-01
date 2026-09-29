package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 放行审签审计事件（append-only，完整时间线）。
 *
 * <p>创建、提交、机械放行、质量终审通过、驳回、失效、重新提交、出位……
 * 每个动作追加一行，谁（operator）在什么时候（eventAt）因为什么（detail）做的，
 * 全程只追加不改写。记录失效后已有签字事件仍保留作审计，但不得再沿用。
 */
@Data
@Entity
@Table(name = "release_event")
public class ReleaseEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /**
     * 事件类型：CREATE / SUBMIT / MECH_SIGN / QA_SIGN / REJECT / INVALIDATE / DEPART。
     * 与单据状态流转一一对应（驳回记 REJECT，重新提交仍记 SUBMIT 但带新版本号）。
     */
    @Column(name = "event_type", nullable = false, length = 16)
    private String eventType;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    @Column(name = "detail", length = 512)
    private String detail;

    /** 该事件对应的快照版本（创建/提交时有值），其余可空 */
    @Column(name = "snapshot_version")
    private Integer snapshotVersion;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;
}
