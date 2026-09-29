package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.Bay;

public interface BayRepository extends JpaRepository<Bay, Long> {

    List<Bay> findAllByOrderByBayNoAsc();

    Optional<Bay> findByBayNo(String bayNo);

    boolean existsByBayNo(String bayNo);

    /** 一架飞机是否已经占了别的机位（换机位入位时用） */
    List<Bay> findAllByCurrentAircraftId(Long aircraftId);

    long countByStatus(String status);
}
