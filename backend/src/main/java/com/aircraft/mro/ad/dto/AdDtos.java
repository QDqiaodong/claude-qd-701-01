package com.aircraft.mro.ad.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块七入参。 */
public final class AdDtos {

    private AdDtos() {
    }

    /** 范围行入参 */
    @Data
    public static class ScopeReq {
        @NotBlank(message = "范围机型不能为空")
        private String model;
        /** 空串/null 表示不设边界 */
        private String tailLower;
        private String tailUpper;
    }

    /** 新建指令（同时创建首个修订版） */
    @Data
    public static class CreateDirectiveReq {
        @NotBlank(message = "适航指令编号不能为空")
        private String adNo;
        @NotBlank(message = "指令标题不能为空")
        private String title;
        @NotBlank(message = "发布机构不能为空")
        private String issuer;
        @NotBlank(message = "首版修订号不能为空")
        private String revisionNo;
        private LocalDate effectiveDate;
        private LocalDate dueDate;
        private BigDecimal dueFh;
        private Integer dueCycles;
        private Integer warnDays;
        private BigDecimal warnFh;
        private Integer warnCycles;
        /** 只在验收演示时使用：首次批量重算强制失败的机号列表 */
        private String demoFailTails;
        private List<ScopeReq> scopes;
    }

    /** 新增修订版（旧版冻结，新版按新范围/阈值全机队重算） */
    @Data
    public static class ReviseReq {
        @NotBlank(message = "新修订版号不能为空")
        private String revisionNo;
        private LocalDate effectiveDate;
        private LocalDate dueDate;
        private BigDecimal dueFh;
        private Integer dueCycles;
        private Integer warnDays;
        private BigDecimal warnFh;
        private Integer warnCycles;
        private List<ScopeReq> scopes;
    }

    /** 编辑当前生效版的适用范围与阈值（不改修订号；触发该机队重算） */
    @Data
    public static class EditRevisionReq {
        private LocalDate effectiveDate;
        private LocalDate dueDate;
        private BigDecimal dueFh;
        private Integer dueCycles;
        private Integer warnDays;
        private BigDecimal warnFh;
        private Integer warnCycles;
        private List<ScopeReq> scopes;
    }

    /** 落实请求：至少一张同机已关闭工卡；证据二选一 */
    @Data
    public static class ComplyReq {
        @NotBlank(message = "落实操作人不能为空")
        private String operator;
        /** 至少一张 */
        private List<Long> cardIds;
        /** 登记的外部凭据编号（与文件至少给一种，由服务端校验） */
        private String evidenceRefNo;
        private String evidenceNote;
    }

    /** 再挂一张工卡 / 追加登记证据 */
    @Data
    public static class LinkCardReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
        private Long cardId;
    }

    @Data
    public static class AddEvidenceReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
        @NotBlank(message = "证据凭据编号/归档号不能为空")
        private String refNo;
        private String note;
    }

    @Data
    public static class MarkEvidenceMissingReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
        private String reason;
    }

    @Data
    public static class ReverifyReq {
        @NotBlank(message = "核实人不能为空")
        private String operator;
        private String note;
    }
}
