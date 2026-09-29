package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 适航指令（编号头）：一个编号下可以有多个修订版 {@link AdRevision}。 */
@Data
@Entity
@Table(name = "ad_directive")
public class AdDirective {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 适航指令编号，如 CAD-2026-001 */
    @Column(name = "ad_no", nullable = false, length = 32)
    private String adNo;

    /** 发布机构：CAAC 华东局 / EASA / FAA … */
    @Column(name = "authority", nullable = false, length = 64)
    private String authority;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** 当前最新修订版号 */
    @Column(name = "latest_revision", nullable = false, length = 16)
    private String latestRevision;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
