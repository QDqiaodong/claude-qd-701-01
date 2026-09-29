package com.aircraft.mro.ad.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 落实清单行：一架飞机 × 一个修订版 = 唯一当前结果（DB 唯一约束兜底）。
 *
 * <p>状态由服务端按当前日期、飞机总飞行小时、总起落、工卡与证据事实计算：
 * NOT_APPLICABLE / NOT_DUE / DUE_SOON / OVERDUE / PENDING_VERIFY / COMPLIED。
 *
 * <p>{@link #factVersion} 是计算依据版本：每次输入事实变化驱动的重算 +1，
 * 旧版本结果不能覆盖新版本；{@link #factSig} 是输入签名，输入未变的重算不产生新事件。
 */
@Data
@Entity
@Table(name = "ad_compliance")
public class AdCompliance {

    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String NOT_DUE = "NOT_DUE";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String OVERDUE = "OVERDUE";
    public static final String PENDING_VERIFY = "PENDING_VERIFY";
    public static final String COMPLIED = "COMPLIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "applicable", nullable = false)
    private Integer applicable;

    /** 最早限制维度：DATE / FH / CYCLE */
    @Column(name = "governing_kind", length = 8)
    private String governingKind;

    /** 日历余量（天，负数=超期） */
    @Column(name = "days_left")
    private Integer daysLeft;

    @Column(name = "fh_left", precision = 12, scale = 2)
    private BigDecimal fhLeft;

    @Column(name = "cycles_left")
    private Integer cyclesLeft;

    /** 待核实原因：累计值缺失 / 边界不完整 / 阈值矛盾 / 工卡变化 / 证据缺失…，多条分号拼接 */
    @Column(name = "pending_reason", length = 512)
    private String pendingReason;

    @Column(name = "compiled_at")
    private LocalDateTime compiledAt;

    @Column(name = "compiled_by", length = 32)
    private String compiledBy;

    @Column(name = "fact_sig", length = 64)
    private String factSig;

    @Column(name = "fact_version", nullable = false)
    private Long factVersion;

    @Column(name = "last_job_item_id")
    private Long lastJobItemId;

    @Column(name = "computed_at", nullable = false)
    private LocalDateTime computedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
