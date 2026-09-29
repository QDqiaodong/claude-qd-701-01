package com.aircraft.mro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 模块七（适航指令落实追踪）入参。 */
public final class AdDtos {

    private AdDtos() {
    }

    /** 新建指令（含首个 R0 草稿内容，可直接发布） */
    @Data
    public static class DirectiveCreateReq {
        @NotBlank(message = "适航指令编号不能为空")
        private String adNo;
        @NotBlank(message = "发布机构不能为空")
        private String authority;
        @NotBlank(message = "指令标题不能为空")
        private String title;
        /** 修订内容（作为 R0） */
        @NotNull(message = "首版内容不能为空")
        private RevisionContent revision;
        /** true=创建后直接生效（默认）；false=保存草稿 */
        private Boolean publish;
    }

    /** 修订版内容（新建 / 修订 / 草稿编辑共用） */
    @Data
    public static class RevisionContent {
        @NotBlank(message = "修订版标题不能为空")
        private String title;
        @NotNull(message = "生效日期不能为空")
        private LocalDate effectiveDate;
        /** 适用机型数组，如 ["B737","A320"] */
        @NotNull(message = "至少选择一个适用机型")
        private List<String> applicableModels;
        private String tailRangeFrom;
        private String tailRangeTo;
        private LocalDate dueDate;
        private BigDecimal dueFh;
        private Integer dueCycles;
        private Integer warnDays;
        private BigDecimal warnFh;
        private Integer warnCycles;
        private String revisionNote;
    }

    /** 编辑草稿内容 */
    @Data
    public static class DraftUpdateReq {
        @NotNull(message = "修订内容不能为空")
        private RevisionContent revision;
    }

    /** 指令头基础信息编辑（机构 / 标题，不动版本） */
    @Data
    public static class DirectiveUpdateReq {
        @NotBlank(message = "发布机构不能为空")
        private String authority;
        @NotBlank(message = "指令标题不能为空")
        private String title;
    }

    /** 基于当前生效版创建下一修订版草稿（可带调整后的范围/阈值） */
    @Data
    public static class NewRevisionReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
        /** 新内容；为空则拷贝当前生效版 */
        private RevisionContent revision;
    }

    @Data
    public static class PublishReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 登记落实：至少一张同机已关闭工卡 + 至少一条证据（登记或先上传） */
    @Data
    public static class ComplyReq {
        @NotBlank(message = "登记人不能为空")
        private String operator;
        @NotNull(message = "至少关联一张已关闭工卡")
        private List<Long> workCardIds;
        /** 文本证据条目（可选多条；与已上传文件证据合计至少一条） */
        private List<EvidenceItem> evidences;
    }

    @Data
    public static class EvidenceItem {
        @NotBlank(message = "证据标题不能为空")
        private String title;
        /** 登记类证据的可追溯编号/引用 */
        private String evidenceRef;
    }

    @Data
    public static class RegisterEvidenceReq {
        @NotBlank(message = "登记人不能为空")
        private String operator;
        @NotBlank(message = "证据标题不能为空")
        private String title;
        @NotBlank(message = "证据登记号/引用不能为空")
        private String evidenceRef;
    }

    @Data
    public static class VoidEvidenceReq {
        @NotBlank(message = "操作人不能为空")
        private String operator;
        @NotBlank(message = "作废原因不能为空")
        private String reason;
    }

    /** 创建批量重算作业 */
    @Data
    public static class RecalcJobReq {
        /** REVISION（默认，按修订版覆盖机队）/ FLEET（全部生效版 × 全机队）/ AIRCRAFT */
        private String scopeType;
        private Long revisionId;
        private Long aircraftId;
        @NotBlank(message = "操作人不能为空")
        private String operator;
        private String reason;
        /** 验收用：注入失败的飞机 id（该项首次处理时报错，续跑成功，不重复生成结果） */
        private List<Long> failInjectAircraftIds;
    }
}
