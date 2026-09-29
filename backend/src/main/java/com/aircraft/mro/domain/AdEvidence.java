package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 落实证据：登记文本或上传文件；可作废（voidFlag=1）但行不删除。 */
@Data
@Entity
@Table(name = "ad_evidence")
public class AdEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compliance_id", nullable = false)
    private Long complianceId;

    /** REGISTER 登记文本 / FILE 上传文件 */
    @Column(name = "evidence_type", nullable = false, length = 16)
    private String evidenceType;

    @Column(name = "title", nullable = false, length = 128)
    private String title;

    /** 文本登记号或文件存储路径 */
    @Column(name = "evidence_ref", length = 255)
    private String evidenceRef;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "registered_by", nullable = false, length = 32)
    private String registeredBy;

    @Column(name = "registered_at", nullable = false)
    private LocalDateTime registeredAt;

    @Column(name = "void_flag", nullable = false)
    private Integer voidFlag;

    @Column(name = "void_reason", length = 255)
    private String voidReason;

    @Column(name = "voided_at")
    private LocalDateTime voidedAt;
}
