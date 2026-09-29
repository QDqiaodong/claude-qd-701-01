package com.aircraft.mro.ad.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.aircraft.mro.ad.domain.AdCompliance;
import com.aircraft.mro.ad.domain.AdComplianceCard;
import com.aircraft.mro.ad.domain.AdEvidence;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.repository.AdComplianceCardRepository;
import com.aircraft.mro.ad.repository.AdComplianceRepository;
import com.aircraft.mro.ad.repository.AdEvidenceRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WorkCardRepository;

import java.nio.file.Path;

/**
 * 协调器判定联动测试（无数据库）：
 *  - 普通行随累计值在 NOT_DUE/DUE_SOON/OVERDUE 之间收敛；
 *  - 已落实行不会被一次飞行登记冲掉；
 *  - 关联工卡重新打开/改挂/取消、证据缺失 → 回待核实。
 */
@org.mockito.junit.jupiter.MockitoSettings(strictness = org.mockito.quality.Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AdComplianceCoordinatorTest {

    @TempDir
    Path evidenceDir;

    @Mock AdRevisionRepository revisionRepository;
    @Mock AdScopeRepository scopeRepository;
    @Mock AdComplianceRepository complianceRepository;
    @Mock AdComplianceCardRepository cardLinkRepository;
    @Mock AdEvidenceRepository evidenceRepository;
    @Mock AdEventRepository eventRepository;
    @Mock AircraftRepository aircraftRepository;
    @Mock WorkCardRepository workCardRepository;
    @Mock AdViewAssembler assembler;

    AdComplianceCoordinator coordinator;

    private AdRevision rev;

    @BeforeEach
    void setup() {
        coordinator = new AdComplianceCoordinator(
                revisionRepository, scopeRepository, complianceRepository, cardLinkRepository,
                evidenceRepository, eventRepository, aircraftRepository, workCardRepository,
                new AdStatusEngine(), assembler, null, evidenceDir.toString());
        rev = new AdRevision();
        rev.setId(10L);
        rev.setDirectiveId(1L);
        rev.setRevisionNo("0");
        rev.setStatus("ACTIVE");
        rev.setEffectiveDate(LocalDate.now().minusDays(100));
        // 仅日历日期阈值：10 天后到期，窗口 20 天 → 当前 DUE_SOON
        rev.setDueDate(LocalDate.now().plusDays(10));
        rev.setWarnDays(20);
        when(revisionRepository.findById(10L)).thenReturn(Optional.of(rev));
        when(scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(10L))
                .thenReturn(List.of(scope("B737", null, null)));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AdScope scope(String model, String lower, String upper) {
        AdScope s = new AdScope();
        s.setModel(model);
        s.setTailLower(lower);
        s.setTailUpper(upper);
        s.setSeqNo(1);
        return s;
    }

    private Aircraft aircraft(BigDecimal fh, Integer cycles) {
        Aircraft a = new Aircraft();
        a.setId(7L);
        a.setTailNo("B-6688");
        a.setModel("B737");
        a.setAirline("航司");
        a.setTotalFh(fh);
        a.setTotalCycles(cycles);
        return a;
    }

    private AdCompliance row(String status) {
        AdCompliance c = new AdCompliance();
        c.setId(100L);
        c.setRevisionId(10L);
        c.setAircraftId(7L);
        c.setStatus(status);
        c.setApplicable(1);
        c.setFactVersion(1L);
        c.setFactSig("old");
        c.setComputedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return c;
    }

    private AdComplianceCard link() {
        AdComplianceCard l = new AdComplianceCard();
        l.setId(1L);
        l.setComplianceId(100L);
        l.setCardId(50L);
        l.setCardNo("WC-1");
        return l;
    }

    private WorkCard card(String status, Long aircraftId) {
        WorkCard c = new WorkCard();
        c.setId(50L);
        c.setCardNo("WC-1");
        c.setAircraftId(aircraftId);
        c.setStatus(status);
        return c;
    }

    private AdEvidence evidence(boolean missing) {
        AdEvidence e = new AdEvidence();
        e.setId(9L);
        e.setComplianceId(100L);
        e.setEvType(AdEvidence.REGISTER);
        e.setRefNo("REF-1");
        e.setMissing(missing ? 1 : 0);
        return e;
    }

    private void wireCommon(Aircraft a, AdCompliance c, List<AdComplianceCard> links,
                            List<AdEvidence> evidences) {
        when(aircraftRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(a));
        when(complianceRepository.findByRevisionIdAndAircraftIdForUpdate(10L, 7L)).thenReturn(Optional.of(c));
        when(complianceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(cardLinkRepository.findByComplianceId(100L)).thenReturn(links);
        when(evidenceRepository.findByComplianceIdOrderByCreatedAtAsc(100L)).thenReturn(evidences);
        if (!links.isEmpty()) {
            when(workCardRepository.findById(50L)).thenAnswer(inv -> Optional.ofNullable(cardStatusHolder[0]));
        }
    }

    private WorkCard[] cardStatusHolder = new WorkCard[1];

    @Test
    void compliedSurvivesFlightLogWhenCardAndEvidenceIntact() {
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.COMPLIED);
        c.setCompiledAt(LocalDateTime.now().minusDays(1));
        c.setCompiledBy("罗建军");
        cardStatusHolder[0] = card("CLOSED", 7L);
        wireCommon(a, c, List.of(link()), List.of(evidence(false)));

        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", "登记飞行", null);
        assertEquals(AdComplianceStatus.COMPLIED, out.status());
    }

    @Test
    void compliedDropsToPendingWhenCardReopened() {
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.COMPLIED);
        cardStatusHolder[0] = card("IN_PROGRESS", 7L);
        wireCommon(a, c, List.of(link()), List.of(evidence(false)));

        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", "工卡重开", null);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, out.status());
        assertTrue(c.getPendingReason().contains("重新打开"));
    }

    @Test
    void pendingVerifyStaysPendingWhenCardReassigned() {
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.PENDING_VERIFY);
        c.setFactSig("x");
        // 工卡改挂到别的飞机（id=8）
        cardStatusHolder[0] = card("CLOSED", 8L);
        when(aircraftRepository.findById(8L)).thenReturn(Optional.of(aircraft(new BigDecimal("1"), 1)));
        wireCommon(a, c, List.of(link()), List.of(evidence(false)));

        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", "工卡改挂", null);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, out.status());
        assertTrue(c.getPendingReason().contains("改挂"));
    }

    @Test
    void compliedDropsToPendingWhenEvidenceMissing() {
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.COMPLIED);
        cardStatusHolder[0] = card("CLOSED", 7L);
        wireCommon(a, c, List.of(link()), List.of(evidence(true)));

        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", "证据缺失", null);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, out.status());
        assertTrue(c.getPendingReason().contains("证据"));
    }

    @Test
    void compliedDropsToPendingWhenCardCanceled() {
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.COMPLIED);
        cardStatusHolder[0] = card("CANCELED", 7L);
        wireCommon(a, c, List.of(link()), List.of(evidence(false)));

        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", "工卡取消", null);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, out.status());
        assertTrue(c.getPendingReason().contains("取消"));
    }

    @Test
    void normalRowTracksDeadlineStatus() {
        Aircraft a = aircraft(new BigDecimal("100"), 10);
        AdCompliance c = row(AdComplianceStatus.NOT_DUE);
        wireCommon(a, c, List.of(), List.of());

        // 日期阈值 10 天后、窗口 20 天 → 临期
        var out = coordinator.recomputeJoined(10L, 7L, "SYSTEM", null, null);
        assertEquals(AdComplianceStatus.DUE_SOON, out.status());
    }

    @Test
    void reverifyRestoresCompliedAfterCardClosedAgain() {
        // 待核实行（有历史关联），工卡已重新关闭且证据齐全 → 人工重算恢复已落实
        Aircraft a = aircraft(new BigDecimal("30118.90"), 21760);
        AdCompliance c = row(AdComplianceStatus.PENDING_VERIFY);
        cardStatusHolder[0] = card("CLOSED", 7L);
        wireCommon(a, c, List.of(link()), List.of(evidence(false)));

        var out = coordinator.recomputeJoined(10L, 7L, "罗建军", "人工核实", null);
        assertEquals(AdComplianceStatus.COMPLIED, out.status());
    }
}
