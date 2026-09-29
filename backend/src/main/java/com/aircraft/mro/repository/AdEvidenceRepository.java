package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.AdEvidence;

public interface AdEvidenceRepository extends JpaRepository<AdEvidence, Long> {

    List<AdEvidence> findAllByComplianceIdOrderByRegisteredAtAscIdAsc(Long complianceId);
}
