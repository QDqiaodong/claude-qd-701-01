package com.aircraft.mro.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 模块五（航材与借还）入参。 */
public final class PartDtos {

    private PartDtos() {
    }

    @Data
    public static class PartSaveReq {

        @NotBlank(message = "件号不能为空")
        private String partNo;

        @NotBlank(message = "名称不能为空")
        private String name;

        @NotBlank(message = "规格不能为空")
        private String spec;

        /** 可空：默认"件" */
        private String unit;

        /** 可空：默认 0 */
        private Integer stockQty;

        /** 可空：默认 0 */
        private Integer minQty;

        /** 可空：默认 0 */
        private BigDecimal unitPrice;

        private String location;
    }

    @Data
    public static class LoanReq {

        /** 航材件 id，外键 */
        private Long partId;

        @NotBlank(message = "领用人不能为空")
        private String borrower;

        private String purpose;

        /** 可空：默认 1 */
        private Integer quantity;

        /** 库管经办人 */
        private String handler;
    }

    @Data
    public static class ReturnReq {

        /** 可空：不传表示全额归还 */
        private Integer quantity;
    }
}
