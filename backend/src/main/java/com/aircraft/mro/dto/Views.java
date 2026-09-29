package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 出参视图。
 *
 * <p>全部用 record：这些对象只在 service 里构造、直接序列化给前端，不需要 setter。
 * record 的访问器（如 {@code bayNo()}）Jackson 原生支持。
 */
public final class Views {

    private Views() {
    }

    /** 机库机位 */
    public record BayView(
            Long id,
            String bayNo,
            String sizeClass,
            String sizeClassName,
            String fitType,
            BigDecimal lengthM,
            String status,
            String statusName,
            Long currentAircraftId,
            String tailNo,
            String model,
            String airworthyName,
            LocalDateTime updatedAt) {
    }

    /** 飞机台账（工卡汇总列来自 @Subselect 视图实体） */
    public record AircraftView(
            Long id,
            String tailNo,
            String model,
            String airline,
            BigDecimal totalFh,
            Integer totalCycles,
            String airworthy,
            String airworthyName,
            String note,
            Long totalCard,
            Long closedCard,
            Long openCard,
            BigDecimal totalHour,
            String bayNo,
            LocalDateTime updatedAt) {
    }

    /** 定检工卡 */
    public record WorkCardView(
            Long id,
            String cardNo,
            Long aircraftId,
            String tailNo,
            String checkType,
            String checkTypeName,
            String title,
            BigDecimal plannedHour,
            BigDecimal actualHour,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            String statusName,
            String executor,
            String inspector,
            LocalDateTime updatedAt) {
    }

    /** 时间轴节点：派工 → 开工 → 完工 → 检验 → 关闭 */
    public record TimelineNode(
            Long cardId,
            String cardNo,
            String step,
            String label,
            String time,
            String color,
            boolean done) {
    }

    /** 故障保留 */
    public record DefectView(
            Long id,
            String defectNo,
            Long aircraftId,
            String tailNo,
            String ataChapter,
            String description,
            String limitUnit,
            String limitUnitName,
            Integer limitValue,
            String limitText,
            LocalDate foundDate,
            LocalDate dueDate,
            String status,
            String statusName,
            String reporter,
            Long daysLeft,
            boolean overdue) {
    }

    /** 航材件 */
    public record PartView(
            Long id,
            String partNo,
            String name,
            String spec,
            String unit,
            Integer stockQty,
            Integer minQty,
            BigDecimal unitPrice,
            String location,
            boolean lowStock,
            BigDecimal stockValue,
            LocalDateTime updatedAt) {
    }

    /** 航材借还记录 */
    public record LoanView(
            Long id,
            String loanNo,
            Long partId,
            String partNo,
            String partName,
            String unit,
            String borrower,
            String purpose,
            Integer quantity,
            LocalDateTime loanTime,
            LocalDateTime returnTime,
            String handler,
            boolean returned) {
    }

    /** @Subselect 汇总视图的一行（带机号，前端/接口直接读） */
    public record CardSummaryView(
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            Long totalCard,
            Long closedCard,
            Long openCard,
            BigDecimal totalHour) {
    }

    /** 机库总览计数器 */
    public record Overview(
            long bayTotal,
            long bayIdle,
            long bayOccupied,
            long bayDisabled,
            long aircraftTotal,
            long airworthy,
            long grounded,
            long inspection,
            long stored,
            long cardAssigned,
            long cardInProgress,
            long cardWaitInsp,
            long cardClosed,
            long defectOpen,
            long defectOverdue,
            long partTotal,
            long partLow,
            long loanOutstanding) {
    }

    // ================= 模块六：出厂放行审签 =================

    /** 放行页顶部总览计数（待审 / 已失效 / 已放行） */
    public record ReleaseOverview(
            long pendingCount,
            long invalidCount,
            long releasedCount,
            long draftCount,
            long rejectedCount,
            Long currentAircraftPendingId,
            String currentAircraftGateMessage) {
    }

    /** 发起放行前的门禁检查结果（阻断条件全部服务端判定） */
    public record ReleaseGateView(
            Long aircraftId,
            String tailNo,
            String model,
            String airworthy,
            String airworthyName,
            boolean airworthyOk,
            Long bayId,
            String bayNo,
            String bayStatus,
            String bayStatusName,
            boolean occupied,
            long openCardCount,
            long overdueDefectCount,
            long openDefectCount,
            boolean allowed,
            String message,
            List<String> blockers) {
    }

    /** 审签单列表行 */
    public record ReleaseListItem(
            Long id,
            String releaseNo,
            Long aircraftId,
            String tailNo,
            String model,
            String status,
            String statusName,
            String initiator,
            String mechSigner,
            String qaSigner,
            Integer snapshotVersion,
            Integer openCardCount,
            Integer openDefectCount,
            Integer overdueDefectCount,
            String invalidReason,
            LocalDateTime submittedAt,
            LocalDateTime qaSignedAt,
            LocalDateTime departedAt,
            LocalDateTime createdAt) {
    }

    /** 快照里的工卡条目（含与当前事实比对结果） */
    public record SnapshotCardItem(
            Long itemId,
            Long cardId,
            String cardNo,
            String statusCode,
            String statusName,
            String title,
            String executor,
            String inspector,
            boolean closed,
            LocalDateTime sourceUpdatedAt,
            String currentStatusCode,
            String currentStatusName,
            boolean changed,
            String changeKind) {
    }

    /** 快照里的故障条目（含保留依据、终审确认、与当前事实比对结果） */
    public record SnapshotDefectItem(
            Long itemId,
            Long defectId,
            String defectNo,
            String statusCode,
            String statusName,
            String ataChapter,
            String description,
            LocalDate dueDate,
            boolean overdue,
            String retainBasis,
            boolean retainBasisMissing,
            boolean qaConfirmed,
            LocalDateTime sourceUpdatedAt,
            String currentStatusCode,
            String currentStatusName,
            boolean changed,
            String changeKind) {
    }

    /** 快照版本（含明细与差异） */
    public record SnapshotView(
            Long id,
            Integer versionNo,
            String snapshotKind,
            String snapshotKindName,
            String tailNo,
            String model,
            String airworthy,
            String airworthyName,
            Long bayId,
            String bayNo,
            String bayStatus,
            String bayStatusName,
            int openCardCount,
            int openDefectCount,
            int overdueDefectCount,
            LocalDateTime createdAt,
            boolean latest,
            List<SnapshotCardItem> cards,
            List<SnapshotDefectItem> defects) {
    }

    /** 审计时间线节点（append-only） */
    public record ReleaseEventView(
            Long id,
            String eventType,
            String eventName,
            String operator,
            String detail,
            Integer snapshotVersion,
            LocalDateTime eventAt) {
    }

    /** 审签单完整详情：单据头 + 当前事实门禁 + 全部快照版本（含差异）+ 时间线 */
    public record ReleaseDetail(
            Long id,
            String releaseNo,
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            String status,
            String statusName,
            String initiator,
            String mechSigner,
            String qaSigner,
            LocalDateTime submittedAt,
            LocalDateTime mechSignedAt,
            LocalDateTime qaSignedAt,
            String rejectReason,
            String invalidReason,
            LocalDateTime invalidatedAt,
            LocalDateTime departedAt,
            String departedBayNo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean editable,
            ReleaseGateView currentGate,
            List<SnapshotView> snapshots,
            List<ReleaseEventView> events) {
    }

    /** 流转动作结果：把"重复点击返回同一结果 / 冲突操作只成一个"显式告诉前端 */
    public record ReleaseActionResult(
            Long id,
            String releaseNo,
            String status,
            String statusName,
            boolean idempotent,
            String message) {
    }

    // ============================== 模块七：适航指令落实追踪 ==============================

    /** 指令目录行：指令头 + 当前最新生效版的关键信息 */
    public record AdDirectiveView(
            Long id,
            String adNo,
            String authority,
            String title,
            String latestRevision,
            Long currentRevisionId,
            String revisionStatus,
            String revisionStatusName,
            String applicableModels,
            String tailRangeFrom,
            String tailRangeTo,
            LocalDate effectiveDate,
            LocalDate dueDate,
            java.math.BigDecimal dueFh,
            Integer dueCycles,
            Integer warnDays,
            java.math.BigDecimal warnFh,
            Integer warnCycles,
            int revisionCount,
            LocalDateTime publishedAt) {
    }

    /** 修订版（历史版本列表） */
    public record AdRevisionView(
            Long id,
            Long directiveId,
            String adNo,
            String revisionNo,
            String title,
            String status,
            String statusName,
            LocalDate effectiveDate,
            String applicableModels,
            String tailRangeFrom,
            String tailRangeTo,
            LocalDate dueDate,
            java.math.BigDecimal dueFh,
            Integer dueCycles,
            Integer warnDays,
            java.math.BigDecimal warnFh,
            Integer warnCycles,
            String revisionNote,
            boolean frozen,
            LocalDateTime publishedAt) {
    }

    /** 落实清单行（带飞机与阈值余量） */
    public record AdComplianceView(
            Long id,
            Long revisionId,
            Long directiveId,
            String adNo,
            String revisionNo,
            String directiveTitle,
            Long aircraftId,
            String tailNo,
            String model,
            String airline,
            String status,
            String statusName,
            boolean applicable,
            String pendingReasons,
            String limitingDim,
            String limitingDimName,
            Integer daysToDue,
            java.math.BigDecimal fhRemaining,
            Integer cycleRemaining,
            java.math.BigDecimal aircraftFh,
            Integer aircraftCycles,
            boolean fhMissing,
            boolean cyclesMissing,
            Long calcTick,
            LocalDateTime calcAt,
            LocalDateTime compliedAt,
            String compliedBy,
            int validLinkCount,
            int validEvidenceCount) {
    }

    /** 落实工卡关联（含当前工卡事实，前端可直接展示失效原因） */
    public record AdLinkView(
            Long id,
            Long workCardId,
            String cardNo,
            Long linkedAircraftId,
            String linkedTailNo,
            String linkedBy,
            LocalDateTime linkedAt,
            boolean valid,
            String invalidReason,
            LocalDateTime invalidatedAt,
            String currentCardStatus,
            String currentCardStatusName,
            Long currentAircraftId,
            String currentTailNo,
            boolean cardClosed) {
    }

    /** 证据 */
    public record AdEvidenceView(
            Long id,
            String evidenceType,
            String evidenceTypeName,
            String title,
            String evidenceRef,
            String fileName,
            String registeredBy,
            LocalDateTime registeredAt,
            boolean voided,
            String voidReason,
            LocalDateTime voidedAt) {
    }

    /** 审计时间线 */
    public record AdEventView(
            Long id,
            String eventType,
            String eventName,
            Long revisionId,
            Long complianceId,
            Long aircraftId,
            String operator,
            String detail,
            String fromStatus,
            String fromStatusName,
            String toStatus,
            String toStatusName,
            LocalDateTime eventAt) {
    }

    /** 清单详情：清单行 + 关联（含失效历史）+ 证据（含作废历史）+ 时间线 */
    public record AdComplianceDetail(
            AdComplianceView compliance,
            List<AdLinkView> links,
            List<AdEvidenceView> evidence,
            List<AdEventView> events) {
    }

    /** 重算作业行 */
    public record AdRecalcJobView(
            Long id,
            String scopeType,
            String scopeTypeName,
            Long directiveId,
            String adNo,
            String revisionNo,
            Long revisionId,
            Long aircraftId,
            String tailNo,
            String triggerReason,
            String status,
            String statusName,
            int totalCount,
            int successCount,
            int failedCount,
            int naCount,
            boolean running,
            int progressPercent,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String createdBy) {
    }

    /** 重算作业明细（逐机失败原因） */
    public record AdRecalcItemView(
            Long id,
            Long aircraftId,
            String tailNo,
            String model,
            Long revisionId,
            String status,
            String statusName,
            String errorMessage,
            int attemptCount,
            LocalDateTime updatedAt) {
    }

    /** 顶部总览 */
    public record AdOverview(
            long directiveCount,
            long overdue,
            long nearDue,
            long pending,
            long complied,
            long runningJobCount) {
    }
}
