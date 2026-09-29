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
 * 证据快照 · 工卡明细（不可变）。
 *
 * <p>快照时把该机【全部工卡】逐张固化（编号、状态、执行人、检验员、更新时间），
 * {@link #closedFlag} 标记当时是否已闭环；放行总览里"未闭环工卡"直接按它过滤。
 * 全量固化才能在差异视图里同时呈现"新冒出来的工卡 / 状态推进的工卡"。
 */
@Data
@Entity
@Table(name = "release_snapshot_card")
public class ReleaseSnapshotCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "card_no", nullable = false, length = 32)
    private String cardNo;

    /** 当时状态：ASSIGNED / IN_PROGRESS / WAIT_INSP / CLOSED / CANCELED */
    @Column(name = "status_code", nullable = false, length = 16)
    private String statusCode;

    @Column(name = "title", length = 128)
    private String title;

    @Column(name = "executor", length = 32)
    private String executor;

    @Column(name = "inspector", length = 32)
    private String inspector;

    /** 0 = 未闭环（待派工/进行中/待检验），1 = 已闭环（已关闭/已取消） */
    @Column(name = "closed_flag", nullable = false)
    private Integer closedFlag;

    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;
}
