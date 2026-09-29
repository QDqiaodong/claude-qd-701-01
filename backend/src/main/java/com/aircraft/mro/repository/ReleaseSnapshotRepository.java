package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ReleaseSnapshot;

public interface ReleaseSnapshotRepository extends JpaRepository<ReleaseSnapshot, Long> {

    List<ReleaseSnapshot> findAllByReleaseIdOrderByVersionNoAsc(Long releaseId);

    Optional<ReleaseSnapshot> findFirstByReleaseIdOrderByVersionNoDesc(Long releaseId);

    long countByReleaseId(Long releaseId);
}
