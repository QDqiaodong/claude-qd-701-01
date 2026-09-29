package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.AdRecalcJob;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdRecalcJobRepository extends JpaRepository<AdRecalcJob, Long> {

    List<AdRecalcJob> findAllByOrderByStartedAtDescIdDesc();

    List<AdRecalcJob> findAllByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select j from AdRecalcJob j where j.id = :id")
    Optional<AdRecalcJob> findByIdForUpdate(Long id);
}
