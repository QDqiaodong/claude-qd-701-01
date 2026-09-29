package com.aircraft.mro.ad.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 落实关联工卡：落实时必须关联至少一张属于同一架飞机且当时已关闭的工卡。
 *
 * <p>唯一约束 (compliance_id, card_id) 保证重复关联同一张工卡不会生成两条落实/关联记录；
 * 工卡后续被取消、重新打开或改挂到别的飞机时，落实结果转待核实，
 * 但关联行【不删除】——历史关联永远可查。
 */
@Data
@Entity
@Table(name = "ad_compliance_card")
public class AdComplianceCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "compliance_id", nullable = false)
    private Long complianceId;

    @Column(name = "card_id", nullable = false)
    private Long cardId;

    /** 关联时工卡号，固化展示（工卡后续变化不改这里） */
    @Column(name = "card_no", nullable = false, length = 32)
    private String cardNo;

    @Column(name = "linked_at", nullable = false)
    private LocalDateTime linkedAt;

    @Column(name = "linked_by", nullable = false, length = 32)
    private String linkedBy;
}
