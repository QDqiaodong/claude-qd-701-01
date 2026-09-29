package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.AdComplianceLink;

public interface AdComplianceLinkRepository extends JpaRepository<AdComplianceLink, Long> {

    List<AdComplianceLink> findAllByComplianceId(Long complianceId);

    List<AdComplianceLink> findAllByWorkCardId(Long workCardId);

    boolean existsByComplianceIdAndWorkCardId(Long complianceId, Long workCardId);
}
