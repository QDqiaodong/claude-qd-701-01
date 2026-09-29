package com.aircraft.mro.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块六（出厂放行审签）入参。 */
public final class ReleaseDtos {

    private ReleaseDtos() {
    }

    /** 发起放行（生成草稿 + 首版证据快照） */
    @Data
    public static class CreateReq {

        private Long aircraftId;

        @NotBlank(message = "发起人姓名不能为空")
        private String initiator;
    }

    /** 提交进入待机械放行（驳回后再次提交也走它，必须重新生成快照） */
    @Data
    public static class SubmitReq {

        @NotBlank(message = "提交人姓名不能为空")
        private String operator;
    }

    /** 机械放行 / 质量终审 */
    @Data
    public static class SignReq {

        @NotBlank(message = "签字人姓名不能为空")
        private String signer;
    }

    /** 质量终审逐条确认开放故障的保留依据 */
    @Data
    public static class ConfirmDefectReq {

        private Long snapshotDefectId;

        @NotBlank(message = "终审人姓名不能为空")
        private String signer;

        /** true 确认保留依据成立 / false 撤回确认 */
        private Boolean confirmed;
    }

    /** 修订某条开放故障的保留依据（草稿/已驳回状态下由发起人填写） */
    @Data
    public static class RetainBasisReq {

        private Long snapshotDefectId;

        @NotBlank(message = "保留依据不能为空：开放故障必须逐条写明保留依据")
        private String retainBasis;

        @NotBlank(message = "操作人姓名不能为空")
        private String operator;
    }

    /** 驳回：必须留下原因 */
    @Data
    public static class RejectReq {

        @NotBlank(message = "签字人姓名不能为空")
        private String signer;

        @NotBlank(message = "驳回必须填写原因")
        private String reason;
    }
}
