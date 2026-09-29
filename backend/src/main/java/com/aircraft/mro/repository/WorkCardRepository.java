package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.WorkCard;

public interface WorkCardRepository extends JpaRepository<WorkCard, Long> {

    List<WorkCard> findAllByOrderByCardNoAsc();

    List<WorkCard> findAllByAircraftIdOrderByCardNoAsc(Long aircraftId);

    Optional<WorkCard> findByCardNo(String cardNo);

    boolean existsByCardNo(String cardNo);

    long countByStatus(String status);

    long countByAircraftIdAndStatus(Long aircraftId, String status);
}
