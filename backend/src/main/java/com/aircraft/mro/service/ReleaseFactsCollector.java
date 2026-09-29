package com.aircraft.mro.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.DefectRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 放行审签的"当前维修事实"采集器：把散落在飞机台账、机位、工卡、故障四处的事实
 * 集中成一份 {@link ReleaseFacts}，发起门禁、快照生成、终审复核、出位门禁都只用这一份口径，
 * 保证"页面判定 / 快照判定 / 出位判定"完全一致，杜绝前端各查各的。
 */
@Component
public class ReleaseFactsCollector {

    private final AircraftRepository aircraftRepository;
    private final BayRepository bayRepository;
    private final WorkCardRepository workCardRepository;
    private final DefectRepository defectRepository;

    public ReleaseFactsCollector(AircraftRepository aircraftRepository,
                                 BayRepository bayRepository,
                                 WorkCardRepository workCardRepository,
                                 DefectRepository defectRepository) {
        this.aircraftRepository = aircraftRepository;
        this.bayRepository = bayRepository;
        this.workCardRepository = workCardRepository;
        this.defectRepository = defectRepository;
    }

    /** 一份不可再分流的事实快照（内存对象，落库成证据快照前的中间态） */
    public static class ReleaseFacts {
        public Aircraft aircraft;
        public Bay bay;
        public List<WorkCard> cards = new ArrayList<>();
        public List<WorkCard> openCards = new ArrayList<>();
        public List<Defect> defects = new ArrayList<>();
        public List<Defect> openDefects = new ArrayList<>();
        public List<Defect> overdueDefects = new ArrayList<>();
        public LocalDate today;
    }

    /**
     * 采集某架飞机当前事实。
     *
     * @param applyOverdue 为 true 时把"到期日已过但库里还挂 OPEN"的故障按业务口径
     *                     视为超期（缺陷模块列表也是这么自动置位的），不写库，只影响本次判定
     */
    @Transactional(readOnly = true)
    public ReleaseFacts collect(Long aircraftId, boolean applyOverdue) {
        ReleaseFacts facts = new ReleaseFacts();
        facts.aircraft = aircraftRepository.findById(aircraftId).orElse(null);
        facts.today = LocalDate.now();
        List<Bay> bays = bayRepository.findAllByCurrentAircraftId(aircraftId);
        facts.bay = bays.isEmpty() ? null : bays.get(0);
        facts.cards = workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId);
        for (WorkCard card : facts.cards) {
            if (!MroDict.cardClosed(card.getStatus())) {
                facts.openCards.add(card);
            }
        }
        facts.defects = defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        for (Defect defect : facts.defects) {
            if ("CLOSED".equals(defect.getStatus())) {
                continue;
            }
            boolean overdue = "OVERDUE".equals(defect.getStatus())
                    || (applyOverdue && "OPEN".equals(defect.getStatus()) && defect.getDueDate().isBefore(facts.today));
            if (overdue) {
                facts.overdueDefects.add(defect);
            } else {
                facts.openDefects.add(defect);
            }
        }
        return facts;
    }
}
