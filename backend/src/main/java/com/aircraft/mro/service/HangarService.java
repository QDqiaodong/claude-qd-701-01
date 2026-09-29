package com.aircraft.mro.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.domain.Part;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.PartRepository;
import com.aircraft.mro.repository.ToolLoanRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 机库总览：顶栏那排计数器 + 主页面用到的汇总数字。
 * 全部走 repository 的 count 派生查询，低库存这种"两列比较"的用内存过滤。
 */
@Service
public class HangarService {

    private final BayRepository bayRepository;
    private final AircraftRepository aircraftRepository;
    private final WorkCardRepository workCardRepository;
    private final DefectRepository defectRepository;
    private final PartRepository partRepository;
    private final ToolLoanRepository toolLoanRepository;

    public HangarService(BayRepository bayRepository,
                         AircraftRepository aircraftRepository,
                         WorkCardRepository workCardRepository,
                         DefectRepository defectRepository,
                         PartRepository partRepository,
                         ToolLoanRepository toolLoanRepository) {
        this.bayRepository = bayRepository;
        this.aircraftRepository = aircraftRepository;
        this.workCardRepository = workCardRepository;
        this.defectRepository = defectRepository;
        this.partRepository = partRepository;
        this.toolLoanRepository = toolLoanRepository;
    }

    @Transactional(readOnly = true)
    public Views.Overview overview() {
        long bayTotal = bayRepository.count();
        long bayIdle = bayRepository.countByStatus("IDLE");
        long bayOccupied = bayRepository.countByStatus("OCCUPIED");
        long bayDisabled = bayRepository.countByStatus("DISABLED");

        long aircraftTotal = aircraftRepository.count();
        long airworthy = aircraftRepository.countByAirworthy("AIRWORTHY");
        long grounded = aircraftRepository.countByAirworthy("GROUNDED");
        long inspection = aircraftRepository.countByAirworthy("INSPECTION");
        long stored = aircraftRepository.countByAirworthy("STORED");

        long cardAssigned = workCardRepository.countByStatus("ASSIGNED");
        long cardInProgress = workCardRepository.countByStatus("IN_PROGRESS");
        long cardWaitInsp = workCardRepository.countByStatus("WAIT_INSP");
        long cardClosed = workCardRepository.countByStatus("CLOSED");

        long defectOpen = defectRepository.countByStatus("OPEN");
        long defectOverdue = defectRepository.countByStatus("OVERDUE");

        List<Part> parts = partRepository.findAllByOrderByPartNoAsc();
        long partLow = parts.stream().filter(part -> part.getStockQty() < part.getMinQty()).count();

        return new Views.Overview(bayTotal, bayIdle, bayOccupied, bayDisabled,
                aircraftTotal, airworthy, grounded, inspection, stored,
                cardAssigned, cardInProgress, cardWaitInsp, cardClosed,
                defectOpen, defectOverdue, parts.size(), partLow,
                toolLoanRepository.countByReturnTimeIsNull());
    }
}
