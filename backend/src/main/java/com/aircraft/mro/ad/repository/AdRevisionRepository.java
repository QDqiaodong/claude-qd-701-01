package com.aircraft.mro.ad.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import com.aircraft.mro.ad.domain.AdRevision;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface AdRevisionRepository extends JpaRepository<AdRevision, Long> {

    List<AdRevision> findAllByOrderByEffectiveDateDescIdAsc();

    List<AdRevision> findByDirectiveIdOrderByRevisionNoAsc(Long directiveId);

    Optional<AdRevision> findFirstByDirectiveIdAndStatus(Long directiveId, String status);

    /** 编辑范围/阈值、新增修订版时串行化，避免两个编辑动作各自触发的重算交叉 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from AdRevision r where r.id = :id")
    Optional<AdRevision> findByIdForUpdate(Long id);
}
