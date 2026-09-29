package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.ad.domain.AdEvidence;

public interface AdEvidenceRepository extends JpaRepository<AdEvidence, Long> {

    List<AdEvidence> findByComplianceIdOrderByCreatedAtAsc(Long complianceId);
}
