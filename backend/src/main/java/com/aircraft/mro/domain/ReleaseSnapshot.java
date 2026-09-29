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
 * 放行证据快照（版本化、不可变）。
 *
 * <p>每次发起（草稿）和每次提交都生成一个新版本：
 * 首次发起是 v1，被驳回修订后再次提交会产生 v2……只有最新版本参与流转，
 * 旧版本永久保留用于审计与差异比对，上游工卡/故障后续怎么改都不会回写到这里。
 *
 * <p>快照头固化发起时刻的飞机适航状态、所在机位；未闭环工卡与开放/超期故障
 * 分别落在 {@link ReleaseSnapshotCard}、{@link ReleaseSnapshotDefect} 两张明细表里，
 * 每条都带当时的编号、状态与更新时间。
 */
@Data
@Entity
@Table(name = "release_snapshot")
public class ReleaseSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_id", nullable = false)
    private Long releaseId;

    /** 版本号，从 1 起，每次提交 +1 */
    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** DRAFT_SNAPSHOT 发起快照 / SUBMIT_SNAPSHOT 提交快照 */
    @Column(name = "snapshot_kind", nullable = false, length = 16)
    private String snapshotKind;

    /** 机号冗余（飞机台账若被改号，快照里仍保留当时编号） */
    @Column(name = "tail_no", nullable = false, length = 16)
    private String tailNo;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    /** 当时适航状态码 */
    @Column(name = "airworthy", nullable = false, length = 16)
    private String airworthy;

    /** 当时所在机位 id，场外为 NULL */
    @Column(name = "bay_id")
    private Long bayId;

    /** 当时所在机位号，场外为 NULL（机位号是业务编号，固化字符串） */
    @Column(name = "bay_no", length = 16)
    private String bayNo;

    /** 机位当时状态：IDLE / OCCUPIED / DISABLED，场外为 NULL */
    @Column(name = "bay_status", length = 16)
    private String bayStatus;

    /** 快照时未闭环工卡条数 */
    @Column(name = "open_card_count", nullable = false)
    private Integer openCardCount;

    /** 快照时开放故障条数（不含已超期） */
    @Column(name = "open_defect_count", nullable = false)
    private Integer openDefectCount;

    /** 快照时超期故障条数 */
    @Column(name = "overdue_defect_count", nullable = false)
    private Integer overdueDefectCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
