package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 出厂放行审签单（模块六）。
 *
 * <p>状态机：DRAFT 草稿 → WAIT_MECH 待机械放行 → WAIT_QA 待质量终审 → RELEASED 已放行；
 * 任一环节 REJECTED 已驳回后回到草稿可修订；审签期间上游事实变化则 INVALID 已失效。
 *
 * <p>{@link #activeAircraftId} 是"同一架飞机同时只能有一条有效审签"的数据库级保证：
 * 草稿/待机械放行/待质量终审/已放行（尚未出位）时等于 aircraft_id，
 * 已失效/已驳回/已出位后置 NULL，配 UNIQUE 索引在并发下也插不进第二条。
 *
 * <p>{@code version} 是 JPA 乐观锁，和审签行的悲观锁（{@code PESSIMISTIC_WRITE}）一起用：
 * 终审等关键动作先 SELECT ... FOR UPDATE 串行化，重复点击同一动作时第二个事务看到的
 * 已是新状态，按"重复动作幂等 / 冲突动作只成一个"的口径返回。
 */
@Data
@Entity
@Table(name = "release_order")
public class ReleaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 审签单号，如 RO-2026-0001 */
    @Column(name = "release_no", nullable = false, length = 32)
    private String releaseNo;

    @Column(name = "aircraft_id", nullable = false)
    private Long aircraftId;

    /**
     * 有效审签占位：非终态（草稿/待审两环/已放行未出位）= aircraft_id，否则 NULL。
     * UNIQUE 索引保证同机不并存两条有效审签。
     */
    @Column(name = "active_aircraft_id")
    private Long activeAircraftId;

    /** DRAFT / WAIT_MECH / WAIT_QA / RELEASED / REJECTED / INVALID */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 发起人（也是提交人，不能替自己做下一环节） */
    @Column(name = "initiator", nullable = false, length = 32)
    private String initiator;

    /** 机械放行人 */
    @Column(name = "mech_signer", length = 32)
    private String mechSigner;

    /** 质量终审人 */
    @Column(name = "qa_signer", length = 32)
    private String qaSigner;

    /** 提交（进入待机械放行）时间 */
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** 机械放行时间 */
    @Column(name = "mech_signed_at")
    private LocalDateTime mechSignedAt;

    /** 质量终审通过时间 = 放行生效时间 */
    @Column(name = "qa_signed_at")
    private LocalDateTime qaSignedAt;

    /** 驳回原因（最近一次驳回） */
    @Column(name = "reject_reason", length = 255)
    private String rejectReason;

    /** 失效原因：上游哪类事实变了 / 终审时复核不过 */
    @Column(name = "invalid_reason", length = 255)
    private String invalidReason;

    /** 最近一次失效发生的时间 */
    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    /** 出位（放行消费）时间，NULL = 放行仍有效可出位 */
    @Column(name = "departed_at")
    private LocalDateTime departedAt;

    /** 出位机位号（出位时固化，审计用） */
    @Column(name = "departed_bay_no", length = 16)
    private String departedBayNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** JPA 乐观锁版本 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
