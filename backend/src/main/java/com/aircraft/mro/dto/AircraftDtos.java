package com.aircraft.mro.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块二（飞机台账）入参。 */
public final class AircraftDtos {

    private AircraftDtos() {
    }

    @Data
    public static class AircraftSaveReq {

        @NotBlank(message = "机号不能为空")
        private String tailNo;

        @NotBlank(message = "机型不能为空")
        private String model;

        @NotBlank(message = "所属航司不能为空")
        private String airline;

        /** 可空：新机默认 0 */
        private BigDecimal totalFh;

        /** 可空：新机默认 0 */
        private Integer totalCycles;

        /** 可空：默认 AIRWORTHY */
        private String airworthy;

        private String note;
    }

    @Data
    public static class AirworthyReq {

        @NotBlank(message = "目标适航状态不能为空")
        private String airworthy;
    }

    /** 登记一次飞行：累加飞行小时与起落次数 */
    @Data
    public static class FlightLogReq {

        private BigDecimal flightHour;

        private Integer cycles;
    }
}
