package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ToolLoan;

public interface ToolLoanRepository extends JpaRepository<ToolLoan, Long> {

    List<ToolLoan> findAllByOrderByLoanTimeDesc();

    List<ToolLoan> findAllByPartIdOrderByLoanTimeDesc(Long partId);

    Optional<ToolLoan> findByLoanNo(String loanNo);

    boolean existsByLoanNo(String loanNo);

    /** 在借未还的单据 */
    List<ToolLoan> findAllByReturnTimeIsNullOrderByLoanTimeDesc();

    long countByReturnTimeIsNull();
}
