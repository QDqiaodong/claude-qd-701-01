package com.aircraft.mro.ad.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 修订版适用范围行：机型 + 机号闭区间 [tailLower, tailUpper]（按机号字符串比较）。
 *
 * <p>两界皆空 = 该机型全部适用；只填一边 = 边界不完整，命中机型的飞机进入待核实
 * （不能把“范围没写全”误判成“不适用”）。多行并集。
 */
@Data
@Entity
@Table(name = "ad_scope")
public class AdScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "revision_id", nullable = false)
    private Long revisionId;

    @Column(name = "model", nullable = false, length = 16)
    private String model;

    @Column(name = "tail_lower", length = 16)
    private String tailLower;

    @Column(name = "tail_upper", length = 16)
    private String tailUpper;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;
}
