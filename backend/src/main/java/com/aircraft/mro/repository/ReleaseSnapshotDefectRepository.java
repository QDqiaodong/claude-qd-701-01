package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ReleaseSnapshotDefect;

public interface ReleaseSnapshotDefectRepository extends JpaRepository<ReleaseSnapshotDefect, Long> {

    List<ReleaseSnapshotDefect> findAllBySnapshotIdOrderByDefectNoAsc(Long snapshotId);
}
