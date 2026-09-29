package com.aircraft.mro.ad.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.ad.domain.AdScope;

public interface AdScopeRepository extends JpaRepository<AdScope, Long> {

    List<AdScope> findByRevisionIdOrderBySeqNoAscIdAsc(Long revisionId);

    void deleteByRevisionId(Long revisionId);
}
