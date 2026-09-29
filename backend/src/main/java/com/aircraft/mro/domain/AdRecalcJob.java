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
 * 批量重算作业。
 *
 * <p>进度、成功/失败数量全部持久化：刷新页面、重新进入模块看到的进度都以服务端为准。
 * 飞机飞行登记是独立短事务，不等待重算作业，批量任务不会占住普通查询。
 */
@Data
@Entity
@Table(name = "ad_recalc_job")
public class AdRecalcJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** REVISION 按指令修订版 / FLEET 全机队 / AIRCRAFT 单架飞机 */
    @Column(name = "scope_type", nullable = false, length = 16)
    private String scopeType;

    @Column(name = "directive_id")
    private Long directiveId;

    @Column(name = "revision_id")
    private Long revisionId;

    @Column(name = "aircraft_id")
    private Long aircraftId;

    @Column(name = "trigger_reason", nullable = false, length = 255)
    private String triggerReason;

    /** RUNNING / DONE / PARTIAL_FAILED / DONE_WITH_SKIP */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "total_count", nullable = false)
    private Integer totalCount;

    @Column(name = "success_count", nullable = false)
    private Integer successCount;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount;

    /** 不适用项数（也算处理成功） */
    @Column(name = "na_count", nullable = false)
    private Integer naCount;

    /** 验收用：注入失败的飞机 id 列表（逗号分隔），仅在项目未处理过时失败一次 */
    @Column(name = "fail_inject", length = 64)
    private String failInject;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_by", nullable = false, length = 32)
    private String createdBy;
}
