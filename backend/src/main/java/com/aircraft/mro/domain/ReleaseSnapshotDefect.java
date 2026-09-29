package com.aircraft.mro.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 证据快照 · 故障明细（不可变）。
 *
 * <p>快照时固化该机所有【未关闭】故障：开放 OPEN 与超期 OVERDUE 都记录编号、状态、
 * 到期日、更新时间。开放故障允许带保留依据提交（{@link #retainBasis}），
 * 但必须由质量终审逐条确认（{@link #qaConfirmedFlag}）后终审才能通过；
 * 存在超期故障时连提交都不允许。
 *
 * <p>保留依据是可修订的：驳回后发起人可修改，随下一次提交进新版本快照；
 * 一旦终审通过，随该版本快照永久固化。
 */
@Data
@Entity
@Table(name = "release_snapshot_defect")
public class ReleaseSnapshotDefect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false)
    private Long snapshotId;

    @Column(name = "defect_id", nullable = false)
    private Long defectId;

    @Column(name = "defect_no", nullable = false, length = 32)
    private String defectNo;

    /** 当时状态：OPEN / OVERDUE */
    @Column(name = "status_code", nullable = false, length = 16)
    private String statusCode;

    @Column(name = "ata_chapter", length = 16)
    private String ataChapter;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "due_date")
    private LocalDate dueDate;

    /** 0 = 当时开放未超期，1 = 当时已超期 */
    @Column(name = "overdue_flag", nullable = false)
    private Integer overdueFlag;

    /** 开放故障的保留依据（逐条填写，提交前必填） */
    @Column(name = "retain_basis", length = 255)
    private String retainBasis;

    /** 质量终审是否逐条确认：0 未确认 / 1 已确认 */
    @Column(name = "qa_confirmed_flag", nullable = false)
    private Integer qaConfirmedFlag;

    @Column(name = "source_updated_at")
    private LocalDateTime sourceUpdatedAt;
}
