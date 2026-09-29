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

/**
 * 机库机位。
 *
 * <p>状态字段故意不在实体上写默认值：实体默认值会被 Jackson 当成"用户传了"，
 * 局部更新时把别的字段刷回默认值。默认值一律在 service 里给。
 */
@Data
@Entity
@Table(name = "bay")
public class Bay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bay_no", nullable = false, length = 16)
    private String bayNo;

    /** 尺寸等级：A / B / C */
    @Column(name = "size_class", nullable = false, length = 4)
    private String sizeClass;

    /** 可承载机型，分号分隔，如 B737;A320 */
    @Column(name = "fit_type", nullable = false, length = 64)
    private String fitType;

    @Column(name = "length_m", nullable = false, precision = 6, scale = 1)
    private BigDecimal lengthM;

    /** IDLE 空闲 / OCCUPIED 占用 / DISABLED 停用 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** 可空外键：当前占用飞机 id，空闲机位为 null */
    @Column(name = "current_aircraft_id")
    private Long currentAircraftId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
