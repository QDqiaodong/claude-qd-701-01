package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.ad.domain.AdEvent;

public interface AdEventRepository extends JpaRepository<AdEvent, Long> {

    List<AdEvent> findByDirectiveIdOrderByEventAtDescIdDesc(Long directiveId);

    List<AdEvent> findByRevisionIdAndAircraftIdOrderByEventAtAscIdAsc(Long revisionId, Long aircraftId);

    List<AdEvent> findAllByOrderByEventAtDescIdDesc();
}
