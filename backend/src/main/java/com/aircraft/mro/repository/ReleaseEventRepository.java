package com.aircraft.mro.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.ReleaseEvent;

public interface ReleaseEventRepository extends JpaRepository<ReleaseEvent, Long> {

    List<ReleaseEvent> findAllByReleaseIdOrderByEventAtAscIdAsc(Long releaseId);
}
