package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.domain.AdRevision;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdRevisionRepository extends JpaRepository<AdRevision, Long> {

    List<AdRevision> findAllByDirectiveIdOrderByRevisionNoAsc(Long directiveId);

    Optional<AdRevision> findByDirectiveIdAndRevisionNo(Long directiveId, String revisionNo);

    List<AdRevision> findAllByStatusOrderByEffectiveDateDesc(String status);

    List<AdRevision> findAllByOrderByIdAsc();

    /** 发布修订版时对旧版加锁串行化，避免两次发布同时把 R0 留在 EFFECTIVE */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select r from AdRevision r where r.directiveId = :directiveId")
    List<AdRevision> findAllByDirectiveIdForUpdate(Long directiveId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select r from AdRevision r where r.id = :id")
    Optional<AdRevision> findByIdForUpdate(Long id);
}
