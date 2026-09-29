package com.aircraft.mro.ad.domain;

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
 * 批量重算任务：范围（{@link #scopeNote}）、总数 / 成功 / 失败计数、状态全部持久化，
 * 刷新页面 / 重新进入模块看到的进度与服务端完全一致。
 *
 * <p>{@link Version} 乐观锁 + 明细级事务更新计数，保证多个处理环节并发时进度按最新值收敛。
 */
@Data
@Entity
@Table(name = "ad_batch_job")
public class AdBatchJob {

    public static final String RUNNING = "RUNNING";
    public static final String PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "directive_id", nullable = false)
    private Long directiveId;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    /** REVISION / SCOPE / THRESHOLD / MANUAL / RETRY */
    @Column(name = "trigger_type", nullable = false, length = 16)
    private String triggerType;

    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    /** 本次重算范围（人话，如“AD… R2 覆盖机队：B737(全机号)、A320(全机号)，共 26 架”） */
    @Column(name = "scope_note", nullable = false, length = 255)
    private String scopeNote;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Version
    private Long version;
}
