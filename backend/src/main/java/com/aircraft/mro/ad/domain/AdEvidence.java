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
 * 落实证据：登记外部凭据编号（REGISTER）或上传文件（UPLOAD），均可追溯。
 *
 * <p>证据被删除/标记缺失时不物理删除：{@link #missing}=1 留痕，
 * 对应落实结果转为待核实；事件流里能看到证据曾经存在。
 */
@Data
@Entity
@Table(name = "ad_evidence")
public class AdEvidence {

    public static final String REGISTER = "REGISTER";
    public static final String UPLOAD = "UPLOAD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compliance_id", nullable = false)
    private Long complianceId;

    @Column(name = "ev_type", nullable = false, length = 8)
    private String evType;

    /** 登记的外部凭据编号（签署页 / 归档号 / URL…） */
    @Column(name = "ref_no", length = 64)
    private String refNo;

    @Column(name = "file_name", length = 255)
    private String fileName;

    /** 上传文件在服务端的相对存储路径 */
    @Column(name = "stored_path", length = 255)
    private String storedPath;

    @Column(name = "note", length = 255)
    private String note;

    /** 1 = 证据缺失（记录保留，结果转待核实） */
    @Column(name = "missing", nullable = false)
    private Integer missing;

    @Column(name = "uploaded_by", nullable = false, length = 32)
    private String uploadedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
