package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.AdDirective;

public interface AdDirectiveRepository extends JpaRepository<AdDirective, Long> {

    List<AdDirective> findAllByOrderByAdNoAsc();

    Optional<AdDirective> findByAdNo(String adNo);

    boolean existsByAdNo(String adNo);
}
