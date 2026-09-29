package com.aircraft.mro.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 航材借出/归还记录：return_time 为 null 表示在借未还。 */
@Data
@Entity
@Table(name = "tool_loan")
public class ToolLoan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "loan_no", nullable = false, length = 32)
    private String loanNo;

    @Column(name = "part_id", nullable = false)
    private Long partId;

    @Column(name = "borrower", nullable = false, length = 32)
    private String borrower;

    @Column(name = "purpose", length = 64)
    private String purpose;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "loan_time", nullable = false)
    private LocalDateTime loanTime;

    /** 可空：null 表示尚未归还 */
    @Column(name = "return_time")
    private LocalDateTime returnTime;

    @Column(name = "handler", length = 32)
    private String handler;
}
