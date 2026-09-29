package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.Defect;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    List<Defect> findAllByOrderByDefectNoAsc();

    List<Defect> findAllByAircraftIdOrderByDefectNoAsc(Long aircraftId);

    Optional<Defect> findByDefectNo(String defectNo);

    boolean existsByDefectNo(String defectNo);

    List<Defect> findAllByStatusOrderByDueDateAsc(String status);

    long countByStatus(String status);
}
