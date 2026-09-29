package com.aircraft.mro.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块一（机库机位）入参。 */
public final class BayDtos {

    private BayDtos() {
    }

    @Data
    public static class BaySaveReq {

        @NotBlank(message = "机位编号不能为空")
        private String bayNo;

        /** A / B / C */
        @NotBlank(message = "尺寸等级不能为空")
        private String sizeClass;

        /** 可空：不传时由 service 按尺寸等级推导可承载机型 */
        private String fitType;

        private BigDecimal lengthM;

        /** 可空：不传时默认 IDLE */
        private String status;
    }

    @Data
    public static class OccupyReq {

        /** 安排入位的飞机 id */
        private Long aircraftId;
    }

    @Data
    public static class BayStatusReq {

        @NotBlank(message = "目标状态不能为空")
        private String status;
    }
}
