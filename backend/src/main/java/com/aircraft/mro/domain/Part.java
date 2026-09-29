package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 航材件（含低库存阈值与单价）。 */
@Data
@Entity
@Table(name = "part")
public class Part {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "part_no", nullable = false, length = 32)
    private String partNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "spec", nullable = false, length = 64)
    private String spec;

    @Column(name = "unit", nullable = false, length = 8)
    private String unit;

    @Column(name = "stock_qty", nullable = false)
    private Integer stockQty;

    @Column(name = "min_qty", nullable = false)
    private Integer minQty;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "location", length = 32)
    private String location;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
