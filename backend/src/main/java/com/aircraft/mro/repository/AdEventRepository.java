package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.AdEvent;

public interface AdEventRepository extends JpaRepository<AdEvent, Long> {

    List<AdEvent> findAllByDirectiveIdOrderByEventAtAscIdAsc(Long directiveId);

    List<AdEvent> findAllByComplianceIdOrderByEventAtAscIdAsc(Long complianceId);
}
