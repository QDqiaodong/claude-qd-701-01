package com.aircraft.mro.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.aircraft.mro.domain.Part;

public interface PartRepository extends JpaRepository<Part, Long> {

    List<Part> findAllByOrderByPartNoAsc();

    Optional<Part> findByPartNo(String partNo);

    boolean existsByPartNo(String partNo);

    /** 已断料的件（库存为 0），借出时会被拦 */
    List<Part> findAllByStockQtyOrderByPartNoAsc(int stockQty);
}
