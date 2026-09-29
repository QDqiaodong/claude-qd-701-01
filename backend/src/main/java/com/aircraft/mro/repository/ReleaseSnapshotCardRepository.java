package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ReleaseSnapshotCard;

public interface ReleaseSnapshotCardRepository extends JpaRepository<ReleaseSnapshotCard, Long> {

    List<ReleaseSnapshotCard> findAllBySnapshotIdOrderByCardNoAsc(Long snapshotId);
}
