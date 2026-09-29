package com.aircraft.mro.ad.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.ad.domain.AdBatchJob;
import com.aircraft.mro.ad.domain.AdCompliance;
import com.aircraft.mro.ad.domain.AdDirective;
import com.aircraft.mro.ad.domain.AdEvidence;
import com.aircraft.mro.ad.domain.AdEvent;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.repository.AdBatchJobRepository;
import com.aircraft.mro.ad.repository.AdComplianceRepository;
import com.aircraft.mro.ad.repository.AdDirectiveRepository;
import com.aircraft.mro.ad.repository.AdEvidenceRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.repository.AircraftRepository;

/**
 * 模块七读侧 + 启动同步。
 *
 * <p>启动时做两件事：①恢复上次异常退出遗留的批量任务；②确保当前生效修订版对每架飞机
 * 都有清单行并按服务端当前日期重算——所以种子数据用相对日期写出后，无论哪天验收，
 * 余量与状态都与服务端一致，刷新/重进模块不会出现前端一套、后端一套。
 */
@Service
public class AdQueryService {

    private final AdDirectiveRepository directiveRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdScopeRepository scopeRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdEventRepository eventRepository;
    private final AdEvidenceRepository evidenceRepository;
    private final AircraftRepository aircraftRepository;
    private final AdBatchJobRepository batchJobRepository;
    private final AdViewAssembler assembler;
    private final AdComplianceCoordinator coordinator;
    private final AdBatchService batchService;

    public AdQueryService(AdDirectiveRepository directiveRepository,
                          AdRevisionRepository revisionRepository,
                          AdScopeRepository scopeRepository,
                          AdComplianceRepository complianceRepository,
                          AdEventRepository eventRepository,
                          AdEvidenceRepository evidenceRepository,
                          AircraftRepository aircraftRepository,
                          AdBatchJobRepository batchJobRepository,
                          AdViewAssembler assembler,
                          AdComplianceCoordinator coordinator,
                          AdBatchService batchService) {
        this.directiveRepository = directiveRepository;
        this.revisionRepository = revisionRepository;
        this.scopeRepository = scopeRepository;
        this.complianceRepository = complianceRepository;
        this.eventRepository = eventRepository;
        this.evidenceRepository = evidenceRepository;
        this.aircraftRepository = aircraftRepository;
        this.batchJobRepository = batchJobRepository;
        this.assembler = assembler;
        this.coordinator = coordinator;
        this.batchService = batchService;
    }

    // ============================== 落实清单（多维筛选） ==============================

    @Transactional(readOnly = true)
    public List<AdViews.ComplianceView> complianceList(Long directiveId, Long revisionId, String model,
                                                       String tailNo, String status, boolean includeHistory) {
        List<AdCompliance> rows;
        if (revisionId != null) {
            rows = complianceRepository.findByRevisionId(revisionId);
        } else {
            rows = complianceRepository.findAll();
            // 当前生效修订版 id 集合（用于“默认只看当前版”）
            List<Long> currentIds = new ArrayList<>();
            for (AdDirective d : directiveRepository.findAllByOrderByAdNoAsc()) {
                if (d.getCurrentRevId() != null) {
                    currentIds.add(d.getCurrentRevId());
                }
            }
            if (directiveId != null) {
                List<Long> revIds = revisionRepository.findByDirectiveIdOrderByRevisionNoAsc(directiveId)
                        .stream().map(AdRevision::getId).toList();
                if (!includeHistory) {
                    revIds = revIds.stream().filter(currentIds::contains).toList();
                }
                List<Long> finalRevIds = revIds;
                rows = rows.stream().filter(r -> finalRevIds.contains(r.getRevisionId())).toList();
            } else if (!includeHistory) {
                // 默认只看当前生效版（跨全部指令）
                rows = rows.stream().filter(r -> currentIds.contains(r.getRevisionId())).toList();
            }
        }
        List<AdViews.ComplianceView> views = assembler.complianceRows(rows);
        return views.stream()
                .filter(v -> model == null || model.isBlank() || model.equalsIgnoreCase(v.model()))
                .filter(v -> tailNo == null || tailNo.isBlank()
                        || (v.tailNo() != null && v.tailNo().toLowerCase().contains(tailNo.trim().toLowerCase())))
                .filter(v -> status == null || status.isBlank() || status.equals(v.status()))
                .sorted((a, b) -> {
                    int c = a.adNo().compareToIgnoreCase(b.adNo() == null ? "" : b.adNo());
                    if (c != 0) {
                        return c;
                    }
                    return a.tailNo().compareToIgnoreCase(b.tailNo() == null ? "" : b.tailNo());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public AdViews.ComplianceView compliance(Long complianceId) {
        AdCompliance row = complianceRepository.findById(complianceId)
                .orElseThrow(() -> new BizException("落实记录不存在：id=" + complianceId));
        return assembler.complianceView(row);
    }

    /** 落实时可选工卡：同机且已关闭。 */
    @Transactional(readOnly = true)
    public List<AdViews.ClosedCardView> closedCards(Long aircraftId) {
        if (aircraftRepository.findById(aircraftId).isEmpty()) {
            throw new BizException("飞机不存在：id=" + aircraftId);
        }
        return assembler.closedCards(aircraftId);
    }

    // ============================== 事件流 ==============================

    @Transactional(readOnly = true)
    public List<AdViews.EventView> events(Long directiveId, Long revisionId, Long aircraftId) {
        List<AdEvent> events;
        if (revisionId != null && aircraftId != null) {
            events = eventRepository.findByRevisionIdAndAircraftIdOrderByEventAtAscIdAsc(revisionId, aircraftId);
        } else if (directiveId != null) {
            events = eventRepository.findByDirectiveIdOrderByEventAtDescIdDesc(directiveId);
        } else {
            events = eventRepository.findAllByOrderByEventAtDescIdDesc();
        }
        if (revisionId != null) {
            events = events.stream().filter(e -> revisionId.equals(e.getRevisionId())).toList();
        }
        if (aircraftId != null) {
            events = events.stream().filter(e -> aircraftId.equals(e.getAircraftId())).toList();
        }
        return assembler.events(events);
    }

    // ============================== 总览 ==============================

    @Transactional(readOnly = true)
    public AdViews.Overview overview() {
        long activeDirectives = 0;
        long overdue = 0;
        long dueSoon = 0;
        long pending = 0;
        long complied = 0;
        long notDue = 0;
        long notApplicable = 0;
        for (AdDirective d : directiveRepository.findAllByOrderByAdNoAsc()) {
            if (d.getCurrentRevId() != null) {
                activeDirectives++;
            }
        }
        for (AdCompliance c : complianceRepository.findAll()) {
            switch (c.getStatus()) {
                case AdComplianceStatus.OVERDUE -> overdue++;
                case AdComplianceStatus.DUE_SOON -> dueSoon++;
                case AdComplianceStatus.PENDING_VERIFY -> pending++;
                case AdComplianceStatus.COMPLIED -> complied++;
                case AdComplianceStatus.NOT_DUE -> notDue++;
                case AdComplianceStatus.NOT_APPLICABLE -> notApplicable++;
                default -> {
                }
            }
        }
        long running = batchJobRepository.findByStatus(AdBatchJob.RUNNING).size();
        return new AdViews.Overview(activeDirectives, overdue, dueSoon, pending, complied, notDue,
                notApplicable, running);
    }

    // ============================== 证据文件下载 ==============================

    @Transactional(readOnly = true)
    public AdEvidence requireEvidence(Long evidenceId) {
        return evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BizException("证据不存在：id=" + evidenceId));
    }

    // ============================== 启动同步 ==============================

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        batchService.recoverOnStartup();
        // 后台线程里做机队初始化重算，避免拖住应用就绪；内部逐架短事务
        AdRecomputeExecutors.run(() -> {
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            seedCurrentFleet();
        });
    }

    /**
     * 保证每个当前生效修订版 × 每架覆盖飞机都有清单行，且按今天的事实收敛。
     * 刻意【不加】类级事务：由后台线程直接调用，每架飞机的 recomputeJoined（REQUIRED）
     * 各自开短事务提交，单架异常不影响其它飞机；种子行状态与当前事实一致时不产生额外事件。
     */
    public void seedCurrentFleet() {
        for (AdRevision rev : revisionRepository.findAllByOrderByEffectiveDateDescIdAsc()) {
            if (!"ACTIVE".equals(rev.getStatus())) {
                continue;
            }
            List<AdScope> scopes = scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(rev.getId());
            for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
                boolean modelMentioned = scopes.stream().anyMatch(s -> s.getModel().equalsIgnoreCase(a.getModel()));
                if (!modelMentioned) {
                    continue;
                }
                coordinator.recomputeJoined(rev.getId(), a.getId(), "SYSTEM",
                        "启动时按服务端当前日期与累计值收敛清单", null);
            }
        }
    }
}
