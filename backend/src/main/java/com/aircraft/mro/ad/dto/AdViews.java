package com.aircraft.mro.ad.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 模块七出参视图（全部 record，只在 service 构造后直接序列化给前端）。
 * 前端看到的状态、余量、进度一律来自这里——服务端是唯一事实源。
 */
public final class AdViews {

    private AdViews() {
    }

    /** 指令目录行 */
    public record DirectiveView(
            Long id,
            String adNo,
            String title,
            String issuer,
            Long currentRevId,
            String currentRevisionNo,
            LocalDate effectiveDate,
            int revisionCount,
            long overdue,
            long dueSoon,
            long pendingVerify,
            long complied,
            long applicableTotal,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 适用范围行 */
    public record ScopeView(
            Long id,
            String model,
            String modelName,
            String tailLower,
            String tailUpper,
            String coverageText) {
    }

    /** 修订版（含范围与阈值） */
    public record RevisionView(
            Long id,
            Long directiveId,
            String adNo,
            String title,
            String issuer,
            String revisionNo,
            String status,
            String statusName,
            LocalDate effectiveDate,
            LocalDate dueDate,
            BigDecimal dueFh,
            Integer dueCycles,
            Integer warnDays,
            BigDecimal warnFh,
            Integer warnCycles,
            String configIssue,
            String demoFailTails,
            List<ScopeView> scopes,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /** 落实记录关联的工卡（含工卡当前事实与是否仍有效） */
    public record ComplianceCardView(
            Long id,
            Long cardId,
            String cardNo,
            String title,
            Long currentAircraftId,
            String currentTailNo,
            String currentStatus,
            String currentStatusName,
            boolean sameAircraft,
            boolean closed,
            boolean valid,
            String invalidReason,
            LocalDateTime linkedAt,
            String linkedBy) {
    }

    /** 落实证据 */
    public record EvidenceView(
            Long id,
            String evType,
            String evTypeName,
            String refNo,
            String fileName,
            String note,
            boolean missing,
            String uploadedBy,
            LocalDateTime createdAt) {
    }

    /** 飞机落实清单行（阈值余量三类各一列） */
    public record ComplianceView(
            Long id,
            Long revisionId,
            Long directiveId,
            String adNo,
            String revisionNo,
            String revStatus,
            Long aircraftId,
            String tailNo,
            String model,
            String modelName,
            String airline,
            BigDecimal totalFh,
            Integer totalCycles,
            boolean totalMissing,
            boolean applicable,
            String status,
            String statusName,
            String governingKind,
            String governingKindName,
            Integer daysLeft,
            BigDecimal fhLeft,
            Integer cyclesLeft,
            String pendingReason,
            LocalDateTime compiledAt,
            String compiledBy,
            long factVersion,
            LocalDateTime computedAt,
            List<ComplianceCardView> cards,
            List<EvidenceView> evidences) {
    }

    /** 状态变化 / 证据 / 关联事件 */
    public record EventView(
            Long id,
            Long directiveId,
            Long revisionId,
            Long aircraftId,
            String tailNo,
            String adNo,
            String revisionNo,
            String eventType,
            String eventName,
            String fromStatus,
            String fromStatusName,
            String toStatus,
            String toStatusName,
            String operator,
            String detail,
            Long batchJobId,
            LocalDateTime eventAt) {
    }

    /** 批量重算明细行 */
    public record BatchItemView(
            Long id,
            Long aircraftId,
            String tailNo,
            String model,
            String status,
            String statusName,
            int attempt,
            String failReason,
            LocalDateTime updatedAt) {
    }

    /** 批量重算任务（范围、进度、成功数量、失败原因全部持久化） */
    public record BatchJobView(
            Long id,
            Long directiveId,
            String adNo,
            Long revisionId,
            String revisionNo,
            String triggerType,
            String triggerName,
            String operator,
            String scopeNote,
            int totalCount,
            int successCount,
            int failedCount,
            int pendingCount,
            String status,
            String statusName,
            String lastError,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            List<BatchItemView> items) {
    }

    /** 落实时可选的同机已关闭工卡 */
    public record ClosedCardView(
            Long cardId,
            String cardNo,
            String title,
            String checkTypeName,
            LocalDateTime endTime,
            String executor,
            String inspector) {
    }

    /** 模块顶部计数 */
    public record Overview(
            long activeDirectiveCount,
            long overdue,
            long dueSoon,
            long pendingVerify,
            long complied,
            long notDue,
            long notApplicable,
            long runningJobCount) {
    }
}
