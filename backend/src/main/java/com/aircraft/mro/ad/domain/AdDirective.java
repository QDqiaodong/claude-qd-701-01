package com.aircraft.mro.ad.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 适航指令头：编号唯一、跨修订版的身份。
 * 每次修订只是新增 {@link AdRevision}，旧版结果与证据永远不被覆盖。
 */
@Data
@Entity
@Table(name = "ad_directive")
public class AdDirective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ad_no", nullable = false, length = 32)
    private String adNo;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 发布机构：CAAC / FAA / EASA … */
    @Column(name = "issuer", nullable = false, length = 64)
    private String issuer;

    /** 当前生效修订版 id；被替代的旧版状态为 SUPERSEDED 且结果冻结 */
    @Column(name = "current_rev_id")
    private Long currentRevId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
