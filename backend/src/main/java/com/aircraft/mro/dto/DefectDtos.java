package com.aircraft.mro.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块四（故障保留）入参。 */
public final class DefectDtos {

    private DefectDtos() {
    }

    @Data
    public static class DefectSaveReq {

        @NotBlank(message = "缺陷单号不能为空")
        private String defectNo;

        /** 飞机 id，外键 */
        private Long aircraftId;

        @NotBlank(message = "ATA 章节不能为空")
        private String ataChapter;

        @NotBlank(message = "故障描述不能为空")
        private String description;

        /** DAY 飞行日 / HOUR 飞行小时 */
        @NotBlank(message = "保留期限单位不能为空")
        private String limitUnit;

        /** 保留期限数值 */
        private Integer limitValue;

        /** 可空：默认今天 */
        private LocalDate foundDate;

        private String reporter;
    }
}
