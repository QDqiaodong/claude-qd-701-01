package com.aircraft.mro.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.domain.AdCompliance;
import com.aircraft.mro.domain.AdComplianceLink;
import com.aircraft.mro.domain.AdDirective;
import com.aircraft.mro.domain.AdEvidence;
import com.aircraft.mro.domain.AdEvent;
import com.aircraft.mro.domain.AdRecalcJob;
import com.aircraft.mro.domain.AdRevision;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.AdComplianceLinkRepository;
import com.aircraft.mro.repository.AdComplianceRepository;
import com.aircraft.mro.repository.AdDirectiveRepository;
import com.aircraft.mro.repository.AdEvidenceRepository;
import com.aircraft.mro.repository.AdEventRepository;
import com.aircraft.mro.repository.AdRecalcJobRepository;
import com.aircraft.mro.repository.AdRevisionRepository;

/**
 * 模块七启动引导（幂等，可反复执行）：
 * <ol>
 *   <li>为所有【生效版】补齐缺失的清单行并按最新数据重算（旧版 SUPERSEDED 冻结不动）；</li>
 *   <li>注入验收所需的"已落实 / 工卡改挂回待核实 / 工卡取消回待核实 / 证据作废回待核实"历史；</li>
 *   <li>首次启动时创建一条基线重算作业（全成功），让页面进度/作业列表开箱即有数据。</li>
 * </ol>
 * 全部用固定编号与存在性判断，重复启动不会重复生成落实记录或事件。
 */
@Component
public class AdBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdBootstrap.class);

    private final AdDirectiveRepository directiveRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdComplianceLinkRepository linkRepository;
    private final AdEvidenceRepository evidenceRepository;
    private final AdRecalcJobRepository jobRepository;
    private final AdEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final AdService adService;

    public AdBootstrap(AdDirectiveRepository directiveRepository,
                       AdRevisionRepository revisionRepository,
                       AdComplianceRepository complianceRepository,
                       AdComplianceLinkRepository linkRepository,
                       AdEvidenceRepository evidenceRepository,
                       AdRecalcJobRepository jobRepository,
                       AdEventRepository eventRepository,
                       AircraftRepository aircraftRepository,
                       AdService adService) {
        this.directiveRepository = directiveRepository;
        this.revisionRepository = revisionRepository;
        this.complianceRepository = complianceRepository;
        this.linkRepository = linkRepository;
        this.evidenceRepository = evidenceRepository;
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.adService = adService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void boot() {
        try {
            seedCredentialHistory();
            ensureBaselineJob();
            List<AdRevision> effective = revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE");
            log.info("[AD-bootstrap] 适航指令模块引导完成，生效修订版 {} 个，基线作业已在事务提交后异步执行",
                    effective.size());
        } catch (Exception e) {
            log.warn("[AD-bootstrap] 引导执行异常（不影响系统启动）: {}", e.getMessage());
        }
    }

    /** 验收种子：已落实 / 改挂 / 取消 / 证据作废 的当前版本历史（幂等）。 */
    @Transactional
    public void seedCredentialHistory() {
        LocalDateTime now = LocalDateTime.now();

        // 1) AD-04（revision 4，CAD-2026-004 R1）× 7 号机 B-7325：凭 WC-2026-0201 落实（有效）
        seedComply(4L, 7L, 25L, "WC-2026-0201", "罗建军",
                "AD 落实工作单签署页（工卡 WC-2026-0201）", "ARC-20260918-004",
                "CAD-2026-004 R1 落实：升降舵铰链检查工卡关闭并归档", now.minusDays(11), true);

        // 2) AD-10（revision 12，CAD-2026-010 R1）× 3 号机 B-6571：曾关联 WC-2026-0203，工卡已改挂 8 号机 → 失效
        seedComply(12L, 3L, 27L, "WC-2026-0203", "周国平",
                "货舱烟雾探测器改装签署页（后被认定工卡不属于本机）", "ARC-20260923-010",
                "CAD-2026-010 R1 历史登记：工卡 WC-2026-0203 改挂后凭证失效", now.minusDays(6), false);

        // 3) AD-11（revision 13，CAD-2026-020 R1）× 3 号机 B-6571：曾关联 WC-2026-0204，工卡已取消 → 失效
        seedComply(13L, 3L, 28L, "WC-2026-0204", "王大鹏",
                "防冰活门管路检查记录（工卡后被取消）", "ARC-20260926-020",
                "CAD-2026-020 R1 历史登记：工卡 WC-2026-0204 已取消，凭证失效", now.minusDays(3), false);

        // 4) AD-01（revision 1，CAD-2026-001 R1）× 1 号机 B-6032：工卡 WC-2026-0001 有效关闭，
        //    但唯一证据已登记作废 → 证据缺失回待核实（关联保留、证据保留）
        seedComply(1L, 1L, 1L, "WC-2026-0001", "罗建军",
                "机翼前梁螺栓检查签署页（原件模糊，已登记作废待补传）", "ARC-20260920-001",
                "CAD-2026-001 R1 历史登记：证据作废，待补有效证据", now.minusDays(9), false);
        voidEvidence(1L, 1L, "原始签署页扫描件模糊无法辨认，登记作废待重新上传", now.minusDays(2));
    }

    /**
     * 幂等写入一条"曾经登记落实"的历史：清单行（若无）+ 工卡关联 + 证据 + 事件。
     * @param credentialStillValid true=凭证仍有效（重算后保持已落实）；false=凭证已失效（重算后回待核实）
     */
    private void seedComply(Long revisionId, Long aircraftId, Long cardId, String cardNo, String operator,
                            String evidenceTitle, String evidenceRef, String complyDetail,
                            LocalDateTime at, boolean credentialStillValid) {
        AdRevision rev = revisionRepository.findById(revisionId).orElse(null);
        if (rev == null) {
            return;
        }
        AdCompliance c = complianceRepository.findByRevisionIdAndAircraftId(revisionId, aircraftId).orElse(null);
        if (c == null) {
            c = new AdCompliance();
            c.setRevisionId(revisionId);
            c.setAircraftId(aircraftId);
            c.setApplicable(1);
            c.setStatus("COMPLIED");
            c.setCalcTick(0L);
            c.setCreatedAt(at);
            c.setUpdatedAt(at);
            c.setCompliedAt(at);
            c.setCompliedBy(operator);
            c = complianceRepository.save(c);
        }
        Long complianceId = c.getId();

        if (linkRepository.findAllByComplianceId(complianceId).stream()
                .noneMatch(l -> l.getWorkCardId().equals(cardId))) {
            AdComplianceLink link = new AdComplianceLink();
            link.setComplianceId(complianceId);
            link.setWorkCardId(cardId);
            link.setCardNo(cardNo);
            // linked_aircraft_id 始终记录"关联时"的归属飞机：改挂场景记录原机
            link.setLinkedAircraftId(aircraftId);
            link.setLinkedBy(operator);
            link.setLinkedAt(at);
            link.setValidFlag(credentialStillValid ? 1 : 0);
            if (!credentialStillValid) {
                link.setInvalidatedAt(LocalDateTime.now());
                // 失效原因交给首次重算的统一口径生成；种子里给一个初始原因保证刷新前也可读
                link.setInvalidReason(cardId.equals(27L)
                        ? "工卡已改挂到其他飞机（当前机号见工卡台账）"
                        : cardId.equals(28L) ? "工卡已被取消" : "凭证失效，待核实");
            }
            linkRepository.save(link);
        }

        boolean hasEvidence = evidenceRepository
                .findAllByComplianceIdOrderByRegisteredAtAscIdAsc(complianceId).stream()
                .anyMatch(e -> evidenceRef.equals(e.getEvidenceRef()));
        if (!hasEvidence) {
            AdEvidence e = new AdEvidence();
            e.setComplianceId(complianceId);
            e.setEvidenceType("REGISTER");
            e.setTitle(evidenceTitle);
            e.setEvidenceRef(evidenceRef);
            e.setRegisteredBy(operator);
            e.setRegisteredAt(at);
            e.setVoidFlag(0);
            evidenceRepository.save(e);
        }

        boolean hasComplyEvent = eventRepository.findAllByComplianceIdOrderByEventAtAscIdAsc(complianceId).stream()
                .anyMatch(ev -> "COMPLY".equals(ev.getEventType()));
        if (!hasComplyEvent) {
            AdEvent event = new AdEvent();
            event.setDirectiveId(rev.getDirectiveId());
            event.setRevisionId(revisionId);
            event.setComplianceId(complianceId);
            event.setAircraftId(aircraftId);
            event.setEventType("COMPLY");
            event.setOperator(operator);
            event.setDetail(complyDetail);
            event.setFromStatus(null);
            event.setToStatus("COMPLIED");
            event.setEventAt(at);
            eventRepository.save(event);
        }
    }

    /** 按 evidenceRef 定位证据并幂等作废。 */
    private void voidEvidence(Long revisionId, Long aircraftId, String reason, LocalDateTime at) {
        AdCompliance c = complianceRepository.findByRevisionIdAndAircraftId(revisionId, aircraftId).orElse(null);
        if (c == null) {
            return;
        }
        AdRevision rev = revisionRepository.findById(revisionId).orElseThrow();
        for (AdEvidence e : evidenceRepository
                .findAllByComplianceIdOrderByRegisteredAtAscIdAsc(c.getId())) {
            if ("ARC-20260920-001".equals(e.getEvidenceRef()) && e.getVoidFlag() == 0) {
                e.setVoidFlag(1);
                e.setVoidReason(reason);
                e.setVoidedAt(at);
                evidenceRepository.save(e);
                boolean hasVoidEvent = eventRepository.findAllByComplianceIdOrderByEventAtAscIdAsc(c.getId())
                        .stream().anyMatch(ev -> "EVIDENCE_VOID".equals(ev.getEventType()));
                if (!hasVoidEvent) {
                    AdEvent event = new AdEvent();
                    event.setDirectiveId(rev.getDirectiveId());
                    event.setRevisionId(revisionId);
                    event.setComplianceId(c.getId());
                    event.setAircraftId(aircraftId);
                    event.setEventType("EVIDENCE_VOID");
                    event.setOperator("质量经理郑洁");
                    event.setDetail("证据「" + e.getTitle() + "」作废：" + reason);
                    event.setFromStatus("COMPLIED");
                    event.setToStatus("PENDING");
                    event.setEventAt(at);
                    eventRepository.save(event);
                }
            }
        }
    }

    /** 首次启动建一条基线重算作业（全量、无注入、全成功），用于展示本次重算范围/进度。 */
    @Transactional
    public void ensureBaselineJob() {
        if (jobRepository.count() > 0) {
            return;
        }
        AdDirective first = directiveRepository.findAllByOrderByAdNoAsc().stream().findFirst().orElse(null);
        if (first == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        AdRecalcJob job = new AdRecalcJob();
        job.setScopeType("FLEET");
        job.setTriggerReason("系统初始化：按全部生效修订版对全机队生成落实清单基线");
        job.setStatus("RUNNING");
        job.setTotalCount(0);
        job.setSuccessCount(0);
        job.setFailedCount(0);
        job.setNaCount(0);
        job.setStartedAt(now);
        job.setCreatedBy("SYSTEM");
        job = jobRepository.save(job);

        List<AdRevision> effective = revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE");
        var aircraft = aircraftRepository.findAllByOrderByTailNoAsc();
        int total = 0;
        for (AdRevision r : effective) {
            for (var a : aircraft) {
                com.aircraft.mro.domain.AdRecalcJobItem item = new com.aircraft.mro.domain.AdRecalcJobItem();
                item.setJobId(job.getId());
                item.setRevisionId(r.getId());
                item.setAircraftId(a.getId());
                item.setStatus("PENDING");
                item.setAttemptCount(0);
                item.setUpdatedAt(now);
                adService.getItemRepository().save(item);
                total++;
            }
        }
        job.setTotalCount(total);
        jobRepository.save(job);

        AdEvent event = new AdEvent();
        event.setDirectiveId(first.getId());
        event.setEventType("RECALC_JOB");
        event.setOperator("SYSTEM");
        event.setDetail("系统初始化基线重算作业 #" + job.getId() + "：全部生效修订版 × 全机队，共 " + total + " 项");
        event.setEventAt(now);
        eventRepository.save(event);

        Long jobId = job.getId();
        adService.scheduleBaseline(jobId);
    }
}
