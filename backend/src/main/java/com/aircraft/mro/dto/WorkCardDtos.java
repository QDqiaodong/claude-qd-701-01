package com.aircraft.mro.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块三（定检工卡）入参。 */
public final class WorkCardDtos {

    private WorkCardDtos() {
    }

    @Data
    public static class CardSaveReq {

        @NotBlank(message = "工卡号不能为空")
        private String cardNo;

        /** 飞机 id，外键：由 service 判 null 再查库 */
        private Long aircraftId;

        @NotBlank(message = "检类不能为空")
        private String checkType;

        @NotBlank(message = "工作内容不能为空")
        private String title;

        /** 可空：默认 0 */
        private BigDecimal plannedHour;

        /** 执行人，可空：不派人也允许建卡（待派工） */
        private String executor;
    }

    @Data
    public static class StartReq {

        @NotBlank(message = "执行人不能为空")
        private String executor;
    }

    @Data
    public static class FinishReq {

        /** 实际工时，可空：不传则由 service 按开工到现在折算 */
        private BigDecimal actualHour;
    }

    @Data
    public static class InspectReq {

        @NotBlank(message = "检验员不能为空")
        private String inspector;
    }

    /** 模块七：已关闭工卡重新打开（退回到待检验） */
    @Data
    public static class ReopenReq {

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }

    /** 模块七：工卡改挂到另一架飞机 */
    @Data
    public static class ReassignReq {

        private Long targetAircraftId;

        @NotBlank(message = "操作人不能为空")
        private String operator;
    }
}
