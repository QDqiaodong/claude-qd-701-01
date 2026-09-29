package com.aircraft.mro;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.AdCompliance;
import com.aircraft.mro.domain.AdComplianceLink;
import com.aircraft.mro.domain.AdDirective;
import com.aircraft.mro.domain.AdEvidence;
import com.aircraft.mro.domain.AdRecalcJob;
import com.aircraft.mro.domain.AdRecalcJobItem;
import com.aircraft.mro.domain.AdRevision;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.dto.AdDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.AdComplianceLinkRepository;
import com.aircraft.mro.repository.AdComplianceRepository;
import com.aircraft.mro.repository.AdDirectiveRepository;
import com.aircraft.mro.repository.AdEvidenceRepository;
import com.aircraft.mro.repository.AdRecalcJobItemRepository;
import com.aircraft.mro.repository.AdRecalcJobRepository;
import com.aircraft.mro.repository.AdRevisionRepository;
import com.aircraft.mro.repository.WorkCardRepository;
import com.aircraft.mro.service.AdBootstrap;
import com.aircraft.mro.service.AdService;
import com.aircraft.mro.service.WorkCardService;

/**
 * 模块七端到端集成测试（H2/MySQL 兼容模式 + JPA 自动建表）：
 * 覆盖适用判断、三类阈值先到、待核实口径、落实凭证、工卡变化回待核实、
 * 历史版本不覆盖、飞行增量重算、批量作业部分失败续跑、tick 收敛。
 */
@SpringBootTest
@ActiveProfiles("adtest")
class AdModuleIntegrationTest {

    @Autowired AdService adService;
    @Autowired WorkCardService workCardService;
    @Autowired AdDirectiveRepository directiveRepository;
    @Autowired AdRevisionRepository revisionRepository;
    @Autowired AdComplianceRepository complianceRepository;
    @Autowired AdComplianceLinkRepository linkRepository;
    @Autowired AdEvidenceRepository evidenceRepository;
    @Autowired AdRecalcJobRepository jobRepository;
    @Autowired AdRecalcJobItemRepository itemRepository;
    @Autowired AircraftRepository aircraftRepository;
    @Autowired WorkCardRepository workCardRepository;

    /** 测试中关闭启动引导（基线作业/种子由各用例自己构造，保证确定性） */
    @TestConfiguration
    static class NoBootstrap {
        @Bean
        @Primary
        AdBootstrap adBootstrap() {
            return new AdBootstrap(null, null, null, null, null, null, null, null, null) {
                @Override
                public void boot() {
                    // no-op
                }
                @Override
                public void seedCredentialHistory() {
                }
                @Override
                public void ensureBaselineJob() {
                }
            };
        }
    }

    private java.util.Map<String, Long> acIds = new java.util.HashMap<>();
    private LocalDateTime now = LocalDateTime.now();

    @BeforeEach
    void clean() {
        itemRepository.deleteAll();
        jobRepository.deleteAll();
        linkRepository.deleteAll();
        evidenceRepository.deleteAll();
        complianceRepository.deleteAll();
        revisionRepository.deleteAll();
        directiveRepository.deleteAll();
        workCardRepository.deleteAll();
        aircraftRepository.deleteAll();
        acIds.clear();
        seedAircraftAndCards();
    }

    private void seedAircraftAndCards() {
        acIds.put("B-6032", aircraft("B-6032", "B737", "24860.50", 18320));
        acIds.put("B-6688", aircraft("B-6688", "B737", "30118.90", 21760));
        acIds.put("B-6108", aircraft("B-6108", "A320", "19742.30", 14015));
        acIds.put("B-6571", aircraft("B-6571", "A330", "41205.80", 9230));
        acIds.put("B-3027", aircraft("B-3027", "ARJ21", "11234.60", 12580));
        acIds.put("B-11099", aircraft("B-11099", "ARJ21", null, null));
        // 1 号机一张已关闭工卡（落实凭证）
        WorkCard card = new WorkCard();
        card.setCardNo("WC-T-0001");
        card.setAircraftId(acIds.get("B-6032"));
        card.setCheckType("SPECIAL");
        card.setTitle("测试关闭工卡");
        card.setPlannedHour(BigDecimal.ONE);
        card.setActualHour(BigDecimal.ONE);
        card.setStatus("CLOSED");
        card.setStartTime(now.minusDays(2));
        card.setEndTime(now.minusDays(1));
        card.setExecutor("陈志强");
        card.setInspector("罗建军");
        card.setUpdatedAt(now.minusDays(1));
        workCardRepository.save(card);
    }

    private Long aircraft(String tail, String model, String fh, Integer cyc) {
        Aircraft a = new Aircraft();
        a.setTailNo(tail);
        a.setModel(model);
        a.setAirline("测试航司");
        a.setTotalFh(fh == null ? null : new BigDecimal(fh));
        a.setTotalCycles(cyc);
        a.setAirworthy("AIRWORTHY");
        a.setUpdatedAt(now);
        return aircraftRepository.save(a).getId();
    }

    private Long createRevision(String adNo, String models, String from, String to,
                                String dueDate, String dueFh, Integer dueCyc,
                                int wd, String wfh, int wc, String effective) {
        AdDirective d = new AdDirective();
        d.setAdNo(adNo);
        d.setAuthority("CAAC");
        d.setTitle(adNo + " 标题");
        d.setLatestRevision("R0");
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d = directiveRepository.save(d);
        AdRevision r = new AdRevision();
        r.setDirectiveId(d.getId());
        r.setRevisionNo("R0");
        r.setTitle(d.getTitle());
        r.setStatus("EFFECTIVE");
        r.setEffectiveDate(LocalDate.parse(effective));
        r.setApplicableModels(models);
        r.setTailRangeFrom(from);
        r.setTailRangeTo(to);
        r.setDueDate(dueDate == null ? null : LocalDate.parse(dueDate));
        r.setDueFh(dueFh == null ? null : new BigDecimal(dueFh));
        r.setDueCycles(dueCyc);
        r.setWarnDays(wd);
        r.setWarnFh(wfh == null ? BigDecimal.ZERO : new BigDecimal(wfh));
        r.setWarnCycles(wc);
        r.setCreatedAt(now);
        r.setPublishedAt(now);
        return revisionRepository.save(r).getId();
    }

    private Views.AdComplianceView statusOf(Long revId, String tail) {
        Long acId = acIds.get(tail);
        adService.ensureAndRecompute(revId, acId, "TEST", "测试初始计算");
        return adService.complianceList(revId, null, null, null, null, false).stream()
                .filter(v -> v.aircraftId().equals(acId)).findFirst().orElseThrow();
    }

    private Long id(String tail) {
        return acIds.get(tail);
    }

    private void waitJobDone(Long jobId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Views.AdRecalcJobView j = adService.job(jobId);
            if (!j.running()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("重算作业 " + jobId + " 超时未完成");
    }

    // ------------------------------------------------------------------

    @Test
    void date_fh_cycle_thresholds_and_applicability() {
        // 日期：今天+15天，30天预警 → B737 临期；FH/CYC 不设
        Long revDate = createRevision("CAD-T-001", "B737", null, null,
                LocalDate.now().plusDays(15).toString(), null, null, 30, null, 0,
                LocalDate.now().minusDays(5).toString());
        assertEquals(MroDict.AD_NEAR_DUE, statusOf(revDate, "B-6032").status());
        // A320 不适用
        adService.ensureAndRecompute(revDate, id("B-6108"), "TEST", null);
        Views.AdComplianceView a320 = adService.complianceList(revDate, null, null, null, null, false)
                .stream().filter(v -> v.aircraftId().equals(id("B-6108"))).findFirst().orElseThrow();
        assertEquals(MroDict.AD_NOT_APPLICABLE, a320.status());

        // FH 先到期：25000 / 30000；1 号机 FH 余量 139 < CYC 余量 11680
        Long revFh = createRevision("CAD-T-002", "B737", null, null, null, "25000", 30000,
                0, "500", 2000, LocalDate.now().minusDays(5).toString());
        Views.AdComplianceView v = statusOf(revFh, "B-6032");
        assertEquals(MroDict.AD_NEAR_DUE, v.status());
        assertEquals("FH", v.limitingDim());
        assertEquals(0, new BigDecimal("139.50").compareTo(v.fhRemaining()));
        // 2 号机 FH 已超
        assertEquals(MroDict.AD_OVERDUE, statusOf(revFh, "B-6688").status());

        // 起落先到期：33000 / 18500；1 号机 CYC 余量 180
        Long revCyc = createRevision("CAD-T-003", "B737", null, null, null, "33000", 18500,
                0, "3000", 800, LocalDate.now().minusDays(5).toString());
        assertEquals("CYCLE", statusOf(revCyc, "B-6032").limitingDim());
    }

    @Test
    void missing_cumulative_and_bad_boundary_and_contradiction_go_pending() throws Exception {
        // 累计值缺失
        Long rev1 = createRevision("CAD-T-101", "ARJ21", null, null,
                LocalDate.now().plusDays(40).toString(), "12000", 13000, 15, "500", 800,
                LocalDate.now().minusDays(5).toString());
        Views.AdComplianceView v = statusOf(rev1, "B-11099");
        assertEquals(MroDict.AD_PENDING, v.status());
        assertTrue(v.pendingReasons().contains("总飞行小时"));
        assertTrue(v.pendingReasons().contains("总起落"));
        assertNotEquals(MroDict.AD_NOT_APPLICABLE, v.status());

        // 边界不完整（只填上界）
        Long rev2 = createRevision("CAD-T-102", "ARJ21", null, "B-11999", null, "12000", 13000,
                0, "500", 0, LocalDate.now().minusDays(5).toString());
        assertEquals(MroDict.AD_PENDING, statusOf(rev2, "B-3027").status());

        // 阈值矛盾（截止日期早于生效日期）
        Long rev3 = createRevision("CAD-T-103", "A320", null, null,
                LocalDate.now().minusDays(8).toString(), "24000", null, 10, "800", 0,
                LocalDate.now().plusDays(10).toString());
        assertEquals(MroDict.AD_PENDING, statusOf(rev3, "B-6108").status());

        // 明确不适用（机型不符）不能被判成待核实
        assertEquals(MroDict.AD_NOT_APPLICABLE, statusOf(rev3, "B-6032").status());
    }

    @Test
    void comply_requires_closed_same_aircraft_card_and_evidence() throws Exception {
        Long revId = createRevision("CAD-T-201", "B737", null, null,
                LocalDate.now().minusDays(2).toString(), null, null, 10, null, 0,
                LocalDate.now().minusDays(10).toString());
        // 初始超期
        Views.AdComplianceView base = statusOf(revId, "B-6032");
        assertEquals(MroDict.AD_OVERDUE, base.status());
        Long complianceId = base.id();
        WorkCard closed = workCardRepository.findByCardNo("WC-T-0001").orElseThrow();

        // 没有证据：拒绝
        AdDtos.ComplyReq bad = new AdDtos.ComplyReq();
        bad.setOperator("罗建军");
        bad.setWorkCardIds(List.of(closed.getId()));
        bad.setEvidences(List.of());
        assertThrows(Exception.class, () -> adService.comply(complianceId, bad));

        // 关联非本机关卡：拒绝（3 号机还没有卡，用不存在 id 也先验机号校验——这里直接造一张 A320 卡）
        WorkCard other = new WorkCard();
        other.setCardNo("WC-T-0002");
        other.setAircraftId(id("B-6108"));
        other.setCheckType("ROUTINE");
        other.setTitle("他机工卡");
        other.setPlannedHour(BigDecimal.ONE);
        other.setActualHour(BigDecimal.ONE);
        other.setStatus("CLOSED");
        other.setStartTime(now.minusDays(2));
        other.setEndTime(now.minusDays(1));
        other.setUpdatedAt(now.minusDays(1));
        workCardRepository.save(other);
        AdDtos.ComplyReq wrongPlane = new AdDtos.ComplyReq();
        wrongPlane.setOperator("罗建军");
        wrongPlane.setWorkCardIds(List.of(other.getId()));
        assertThrows(Exception.class, () -> adService.comply(complianceId, wrongPlane));

        // 正确落实
        AdDtos.ComplyReq ok = new AdDtos.ComplyReq();
        ok.setOperator("罗建军");
        ok.setWorkCardIds(List.of(closed.getId()));
        AdDtos.EvidenceItem ev = new AdDtos.EvidenceItem();
        ev.setTitle("签署页"); ev.setEvidenceRef("ARC-T-001");
        ok.setEvidences(List.of(ev));
        adService.comply(complianceId, ok);
        Views.AdComplianceView after = adService.complianceList(revId, null, null, null, null, false).stream()
                .filter(v -> v.aircraftId().equals(id("B-6032"))).findFirst().orElseThrow();
        assertEquals(MroDict.AD_COMPLIED, after.status());
        assertEquals(1, after.validLinkCount());
        assertEquals(1, after.validEvidenceCount());

        // 重复关联同一工卡不产生两条
        adService.comply(complianceId, ok);
        AdCompliance row = complianceRepository.findByRevisionIdAndAircraftId(revId, id("B-6032")).orElseThrow();
        long linkCount = linkRepository.findAllByComplianceId(row.getId()).stream()
                .filter(l -> l.getWorkCardId().equals(closed.getId())).count();
        assertEquals(1, linkCount);
        // 同一机×同一修订版只有一个当前结果
        assertEquals(1, complianceRepository.findAllByRevisionId(revId).stream()
                .filter(c -> c.getAircraftId().equals(id("B-6032"))).count());
    }

    @Test
    void card_reassign_and_reopen_and_evidence_void_move_back_to_pending_but_keep_history() {
        Long revId = createRevision("CAD-T-301", "B737;A330", null, null,
                LocalDate.now().plusDays(400).toString(), "60000", 32000, 30, "1000", 1500,
                LocalDate.now().minusDays(3).toString());
        Long ac1 = id("B-6032");
        Long ac4 = id("B-6571");
        statusOf(revId, "B-6032"); // 建立 1 号机清单
        AdCompliance c = complianceRepository.findByRevisionIdAndAircraftId(revId, ac1).orElseThrow();
        WorkCard closed = workCardRepository.findByCardNo("WC-T-0001").orElseThrow();

        AdDtos.ComplyReq ok = new AdDtos.ComplyReq();
        ok.setOperator("罗建军");
        ok.setWorkCardIds(List.of(closed.getId()));
        AdDtos.EvidenceItem ev = new AdDtos.EvidenceItem();
        ev.setTitle("签署页"); ev.setEvidenceRef("ARC-T-301");
        ok.setEvidences(List.of(ev));
        adService.comply(c.getId(), ok);
        assertEquals(MroDict.AD_COMPLIED, viewOf(revId, ac1).status());

        // 工卡改挂到 4 号机（A330，也在范围内）→ 1 号机回待核实，关联行保留
        workCardService.reassign(closed.getId(), ac4, "测试调度");
        Views.AdComplianceView moved = viewOf(revId, ac1);
        assertEquals(MroDict.AD_PENDING, moved.status());
        assertTrue(moved.pendingReasons().contains("改挂"));
        AdComplianceLink link = linkRepository.findAllByComplianceId(moved.id()).get(0);
        assertEquals(0, link.getValidFlag());
        assertNotNull(link.getInvalidReason());

        // 改回 1 号机：工卡恢复本机关闭，证据仍在 → 重新落实（幂等复用同一关联行）
        workCardService.reassign(closed.getId(), ac1, "测试调度");
        AdDtos.ComplyReq reok = new AdDtos.ComplyReq();
        reok.setOperator("罗建军");
        reok.setWorkCardIds(List.of(closed.getId()));
        reok.setEvidences(List.of());
        adService.comply(moved.id(), reok);
        assertEquals(MroDict.AD_COMPLIED, viewOf(revId, ac1).status());
        assertEquals(1, linkRepository.findAllByComplianceId(moved.id()).size());

        // 工卡重新打开 → 回待核实
        workCardService.reopen(closed.getId(), "测试经理");
        assertEquals(MroDict.AD_PENDING, viewOf(revId, ac1).status());

        // 工卡重新关闭：单纯复核不会自动恢复已落实（关联已被标记失效），必须重新登记落实
        WorkCard card = workCardRepository.findByCardNo("WC-T-0001").orElseThrow();
        card.setStatus("CLOSED");
        card.setInspector("罗建军");
        card.setUpdatedAt(LocalDateTime.now());
        workCardRepository.save(card);
        adService.recomputeOne(moved.id(), "测试");
        assertEquals(MroDict.AD_PENDING, viewOf(revId, ac1).status());
        // 重新登记落实（关联同一工卡复用同一行，不新增）→ 恢复已落实
        adService.comply(moved.id(), reok);
        assertEquals(MroDict.AD_COMPLIED, viewOf(revId, ac1).status());
        assertEquals(1, linkRepository.findAllByComplianceId(moved.id()).size());
        // 再作废唯一证据 → 回待核实，证据行保留不删
        AdEvidence evidence = evidenceRepository.findAllByComplianceIdOrderByRegisteredAtAscIdAsc(moved.id()).get(0);
        AdDtos.VoidEvidenceReq vr = new AdDtos.VoidEvidenceReq();
        vr.setOperator("质量经理"); vr.setReason("原件模糊");
        adService.voidEvidence(evidence.getId(), vr);
        Views.AdComplianceView voided = viewOf(revId, ac1);
        assertEquals(MroDict.AD_PENDING, voided.status());
        assertTrue(voided.pendingReasons().contains("证据"));
        assertEquals(1, evidenceRepository.findAllByComplianceIdOrderByRegisteredAtAscIdAsc(moved.id()).size());
    }

    private Views.AdComplianceView viewOf(Long revId, Long aircraftId) {
        return adService.complianceList(revId, null, null, null, null, false).stream()
                .filter(v -> v.aircraftId().equals(aircraftId)).findFirst().orElseThrow();
    }

    @Test
    void new_revision_recomputes_fleet_and_keeps_old_history() throws Exception {
        Long r0Id = createRevision("CAD-T-401", "B737", null, null,
                LocalDate.now().minusDays(2).toString(), null, null, 10, null, 0,
                LocalDate.now().minusDays(10).toString());
        statusOf(r0Id, "B-6032");
        statusOf(r0Id, "B-6688");
        AdRevision r0 = revisionRepository.findById(r0Id).orElseThrow();

        // 创建 R1：扩大到 B737;A320，日期改到很久以后
        AdDtos.NewRevisionReq nr = new AdDtos.NewRevisionReq();
        nr.setOperator("工程师");
        AdDtos.RevisionContent c = new AdDtos.RevisionContent();
        c.setTitle(r0.getTitle());
        c.setEffectiveDate(LocalDate.now());
        c.setApplicableModels(List.of("B737", "A320"));
        c.setDueDate(LocalDate.now().plusDays(300));
        c.setWarnDays(10);
        nr.setRevision(c);
        Views.AdRevisionView draft = adService.newRevision(r0.getDirectiveId(), nr);
        AdDtos.PublishReq pub = new AdDtos.PublishReq();
        pub.setOperator("工程师");
        adService.publish(draft.id(), pub);

        // 作业在发布事务提交后由 worker 异步创建：轮询等作业出现
        Long jobId = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<AdRecalcJob> js = jobRepository.findAllByOrderByStartedAtDescIdDesc();
            if (!js.isEmpty()) {
                jobId = js.get(0).getId();
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(jobId, "发布后应创建重算作业");
        waitJobDone(jobId, 10000);

        AdRevision oldR0 = revisionRepository.findById(r0Id).orElseThrow();
        assertEquals("SUPERSEDED", oldR0.getStatus());
        // R0 的旧判断行仍在（历史不覆盖）
        assertEquals(2, complianceRepository.findAllByRevisionId(r0Id).size());
        // R1 新纳入 A320，期限变远 → 未到期
        AdRevision r1 = revisionRepository.findAllByDirectiveIdOrderByRevisionNoAsc(r0.getDirectiveId())
                .stream().filter(r -> "R1".equals(r.getRevisionNo())).findFirst().orElseThrow();
        assertEquals(MroDict.AD_NOT_DUE, viewOf(r1.getId(), id("B-6108")).status());
    }

    @Test
    void flight_log_only_recomputes_that_aircraft_and_tick_advances() {
        Long revId = createRevision("CAD-T-501", "B737", null, null, null, "25000", null,
                0, "500", 0, LocalDate.now().minusDays(5).toString());
        Long ac1 = id("B-6032");
        Long ac2 = id("B-6688");
        statusOf(revId, "B-6032");
        statusOf(revId, "B-6688");
        long tick1 = complianceRepository.findByRevisionIdAndAircraftId(revId, ac1).orElseThrow().getCalcTick();
        long tick2 = complianceRepository.findByRevisionIdAndAircraftId(revId, ac2).orElseThrow().getCalcTick();

        // 1 号机登记一次飞行（模拟 AircraftService.logFlight 同事务的 adService 调用）
        Aircraft a1 = aircraftRepository.findById(ac1).orElseThrow();
        a1.setTotalFh(a1.getTotalFh().add(new BigDecimal("2.00")));
        a1.setTotalCycles(a1.getTotalCycles() + 1);
        aircraftRepository.save(a1);
        int n = adService.recalcAircraftOnFlight(ac1);
        assertTrue(n >= 1);
        AdCompliance c1b = complianceRepository.findByRevisionIdAndAircraftId(revId, ac1).orElseThrow();
        AdCompliance c2b = complianceRepository.findByRevisionIdAndAircraftId(revId, ac2).orElseThrow();
        assertTrue(c1b.getCalcTick() > tick1);
        assertEquals(tick2, c2b.getCalcTick());
        assertEquals(0, new BigDecimal("137.50").compareTo(c1b.getFhRemaining()));
    }

    @Test
    void batch_job_partial_failure_then_resume_succeeds_without_duplicates() throws Exception {
        Long revId = createRevision("CAD-T-601", "B737;A320;A330;ARJ21", null, null,
                LocalDate.now().plusDays(100).toString(), null, null, 10, null, 0,
                LocalDate.now().minusDays(5).toString());

        AdDtos.RecalcJobReq req = new AdDtos.RecalcJobReq();
        req.setScopeType("REVISION");
        req.setRevisionId(revId);
        req.setOperator("工程师");
        req.setReason("部分失败验收");
        req.setFailInjectAircraftIds(List.of(id("B-6032"))); // 注入：首次失败、续跑成功
        Views.AdRecalcJobView job = adService.createJob(req);
        waitJobDone(job.id(), 10000);

        Views.AdRecalcJobView partial = adService.job(job.id());
        assertEquals("PARTIAL_FAILED", partial.status());
        assertEquals(1, partial.failedCount());
        assertEquals(6, partial.totalCount()); // 6 架飞机
        AdRecalcJobItem failedItem = itemRepository.findAllByJobIdAndStatus(job.id(), "FAILED").get(0);
        assertTrue(failedItem.getErrorMessage().contains("注入失败"));

        // 失败发生在清单写入前：其余 5 架有行，1 号机没有
        assertEquals(5, complianceRepository.findAllByRevisionId(revId).size());

        // 续跑失败项（成功项不重跑，不重复生成结果）
        adService.resumeJob(job.id());
        waitJobDone(job.id(), 10000);
        Views.AdRecalcJobView done = adService.job(job.id());
        assertEquals("DONE", done.status());
        assertEquals(0, done.failedCount());
        assertEquals(6, done.successCount());

        // 不重复：每机一行
        assertEquals(6, complianceRepository.findAllByRevisionId(revId).size());
    }

    @Test
    void concurrent_batch_and_flight_converges_to_latest_data() throws Exception {
        Long revId = createRevision("CAD-T-701", "B737", null, null, null, "25000", null,
                0, "500", 0, LocalDate.now().minusDays(5).toString());
        AdDtos.RecalcJobReq req = new AdDtos.RecalcJobReq();
        req.setScopeType("REVISION");
        req.setRevisionId(revId);
        req.setOperator("工程师");
        req.setReason("并发收敛验收");
        Views.AdRecalcJobView job = adService.createJob(req);
        waitJobDone(job.id(), 10000);

        // 作业完成后再把 1 号机累计值更新到已超 → 旧作业结果不得覆盖新结果
        Long ac1 = id("B-6032");
        Aircraft a1 = aircraftRepository.findById(ac1).orElseThrow();
        a1.setTotalFh(new BigDecimal("25050.00"));
        aircraftRepository.save(a1);
        adService.recalcAircraftOnFlight(ac1);

        AdCompliance c = complianceRepository.findByRevisionIdAndAircraftId(revId, ac1).orElseThrow();
        assertEquals(MroDict.AD_OVERDUE, c.getStatus());
        assertEquals(-50, c.getFhRemaining().intValue());
    }
}
