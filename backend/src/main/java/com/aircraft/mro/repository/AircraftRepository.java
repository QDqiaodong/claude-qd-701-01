package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.Aircraft;

public interface AircraftRepository extends JpaRepository<Aircraft, Long> {

    List<Aircraft> findAllByOrderByTailNoAsc();

    Optional<Aircraft> findByTailNo(String tailNo);

    boolean existsByTailNo(String tailNo);

    List<Aircraft> findAllByModelOrderByTailNoAsc(String model);

    long countByAirworthy(String airworthy);
}
