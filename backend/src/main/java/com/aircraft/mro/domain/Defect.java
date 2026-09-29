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

/** 故障保留（缺陷单）。保留期限按飞行日 DAY 或飞行小时 HOUR 计。 */
@Data
@Entity
@Table(name = "defect")
public class Defect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "defect_no", nullable = false, length = 32)
    private String defectNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /** ATA 章节，如 32-11 */
    @Column(name = "ata_chapter", nullable = false, length = 16)
    private String ataChapter;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    /** DAY 飞行日 / HOUR 飞行小时 */
    @Column(name = "limit_unit", nullable = false, length = 8)
    private String limitUnit;

    @Column(name = "limit_value", nullable = false)
    private Integer limitValue;

    @Column(name = "found_date", nullable = false)
    private LocalDate foundDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    /** OPEN 开放 / CLOSED 已关闭 / OVERDUE 超期 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "reporter", length = 32)
    private String reporter;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
