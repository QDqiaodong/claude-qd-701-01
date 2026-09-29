package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 落实-工卡关联。
 *
 * <p>(清单行, 工卡) 唯一：重复关联同一张工卡不会产生两条记录。
 * 工卡被取消 / 重新打开 / 改挂到其他飞机后只把 {@code validFlag} 置 0 并登记原因，
 * 关联行不删除——历史关联不能消失。
 */
@Data
@Entity
@Table(name = "ad_compliance_link")
public class AdComplianceLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compliance_id", nullable = false)
    private Long complianceId;

    @Column(name = "work_card_id", nullable = false)
    private Long workCardId;

    /** 关联时工卡号（固化） */
    @Column(name = "card_no", nullable = false, length = 32)
    private String cardNo;

    /** 关联时工卡所属飞机（用于发现改挂） */
    @Column(name = "linked_aircraft_id", nullable = false)
    private Long linkedAircraftId;

    @Column(name = "linked_by", nullable = false, length = 32)
    private String linkedBy;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    /** 1 当前有效 / 0 已失效，行保留 */
    @Column(name = "valid_flag", nullable = false)
    private Integer validFlag;

    @Column(name = "invalid_reason", length = 255)
    private String invalidReason;

    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;
}
