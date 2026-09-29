package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.ad.domain.AdComplianceCard;

public interface AdComplianceCardRepository extends JpaRepository<AdComplianceCard, Long> {

    List<AdComplianceCard> findByComplianceId(Long complianceId);

    List<AdComplianceCard> findByCardId(Long cardId);

    boolean existsByComplianceIdAndCardId(Long complianceId, Long cardId);
}
