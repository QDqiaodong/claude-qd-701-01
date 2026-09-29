package com.aircraft.mro.ad.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.domain.Aircraft;

/**
 * {@link AdStatusEngine} 判定核心测试，覆盖需求里的验收口径：
 * 日期先到期 / FH 先到期 / 起落先到期 / 范围不适用 / 累计值缺失 /
 * 边界不完整 / 阈值矛盾 / 临期窗口逐维配置。
 */
class AdStatusEngineTest {

    private final AdStatusEngine engine = new AdStatusEngine();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 29);

    private Aircraft aircraft(String tail, String model, String fh, Integer cycles) {
        Aircraft a = new Aircraft();
        a.setId(1L);
        a.setTailNo(tail);
        a.setModel(model);
        a.setAirline("测试航司");
        a.setTotalFh(fh == null ? null : new BigDecimal(fh));
        a.setTotalCycles(cycles);
        a.setAirworthy("AIRWORTHY");
        return a;
    }

    private AdRevision revision(LocalDate dueDate, String dueFh, Integer dueCycles,
                                Integer warnDays, String warnFh, Integer warnCycles) {
        AdRevision r = new AdRevision();
        r.setId(1L);
        r.setDirectiveId(1L);
        r.setRevisionNo("0");
        r.setStatus("ACTIVE");
        r.setEffectiveDate(LocalDate.of(2026, 1, 1));
        r.setDueDate(dueDate);
        r.setDueFh(dueFh == null ? null : new BigDecimal(dueFh));
        r.setDueCycles(dueCycles);
        r.setWarnDays(warnDays);
        r.setWarnFh(warnFh == null ? null : new BigDecimal(warnFh));
        r.setWarnCycles(warnCycles);
        return r;
    }

    private AdScope scope(String model, String lower, String upper) {
        AdScope s = new AdScope();
        s.setModel(model);
        s.setTailLower(lower);
        s.setTailUpper(upper);
        s.setSeqNo(1);
        return s;
    }

    // [验收1] 日期先到期：5 天后到期，窗口 10 天 → 临期
    @Test
    void dateDueSoon() {
        AdRevision rev = revision(LocalDate.of(2026, 10, 4), null, null, 10, null, null);
        var result = engine.evaluate(rev, List.of(scope("B737", "B-5000", "B-9999")),
                aircraft("B-6688", "B737", "30118.90", 21760), TODAY);
        assertEquals(AdComplianceStatus.DUE_SOON, result.rawStatus());
        assertEquals(AdStatusEngine.KIND_DATE, result.governingKind());
        assertEquals(5, result.daysLeft());
    }

    // 日期超期
    @Test
    void dateOverdue() {
        AdRevision rev = revision(LocalDate.of(2026, 9, 20), null, null, 10, null, null);
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-6688", "B737", "30118.90", 21760), TODAY);
        assertEquals(AdComplianceStatus.OVERDUE, result.rawStatus());
        assertTrue(result.daysLeft() < 0);
    }

    // [验收2] 飞行小时先到：日期还远，但 FH 30118.90 ≥ 阈值 30000 → 超期，最早限制=FH
    @Test
    void fhExpiresFirst() {
        AdRevision rev = revision(LocalDate.of(2027, 1, 26), "30000", null, 30, "100", null);
        var result = engine.evaluate(rev,
                List.of(scope("B737", "B-5000", "B-9999"), scope("A320", null, null)),
                aircraft("B-6688", "B737", "30118.90", 21760), TODAY);
        assertEquals(AdComplianceStatus.OVERDUE, result.rawStatus());
        assertEquals(AdStatusEngine.KIND_FH, result.governingKind());
        assertTrue(result.fhLeft().doubleValue() <= 0);
    }

    // FH 临期：剩余落在窗口内但未超期
    @Test
    void fhDueSoon() {
        AdRevision rev = revision(LocalDate.of(2027, 6, 1), "30000", null, 30, "100", null);
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-6688", "B737", "29950.00", 21760), TODAY);
        assertEquals(AdComplianceStatus.DUE_SOON, result.rawStatus());
    }

    // [验收3] 起落次数先到：cycles 22010 ≥ 19000，日期 2027 还很远 → 超期，最早限制=CYCLE
    @Test
    void cyclesExpiresFirst() {
        AdRevision rev = revision(LocalDate.of(2027, 6, 30), null, 19000, 30, null, 1000);
        var result = engine.evaluate(rev,
                List.of(scope("B737", "B-5000", "B-9999"), scope("A320", null, null)),
                aircraft("B-6571", "B737", "41205.80", 22010), TODAY);
        // B-6571 实际机型是 A330，这里构造 B737 行用于阈值判定
        assertEquals(AdComplianceStatus.OVERDUE, result.rawStatus());
        assertEquals(AdStatusEngine.KIND_CYCLE, result.governingKind());
    }

    // [验收4] 范围不适用：机型不在范围；以及机号落在完整区间之外
    @Test
    void notApplicableByModelAndRange() {
        AdRevision rev = revision(LocalDate.of(2026, 10, 4), null, null, 10, null, null);
        var byModel = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-6108", "A320", "19742.30", 14015), TODAY);
        assertEquals(AdComplianceStatus.NOT_APPLICABLE, byModel.rawStatus());
        assertFalse(byModel.applicable());

        // 机号按字符串比较：B-4999 在下界 B-5000 之外（演示用边界机号）
        var byRange = engine.evaluate(rev, List.of(scope("B737", "B-5000", "B-9999")),
                aircraft("B-4999", "B737", "12050.00", 13010), TODAY);
        assertEquals(AdComplianceStatus.NOT_APPLICABLE, byRange.rawStatus());

        var inRange = engine.evaluate(rev, List.of(scope("B737", "B-5000", "B-9999")),
                aircraft("B-6032", "B737", "24860.50", 18320), TODAY);
        assertTrue(inRange.applicable());
        // 边界值本身属于闭区间
        var onLowerBound = engine.evaluate(rev, List.of(scope("B737", "B-5000", "B-9999")),
                aircraft("B-5000", "B737", "1.00", 1), TODAY);
        assertTrue(onLowerBound.applicable());
    }

    // [验收5] 累计值缺失：配了 FH/起落阈值但飞机累计值 NULL → 待核实，且不能误判为不适用
    @Test
    void missingTotalsPendingVerify() {
        AdRevision rev = revision(LocalDate.of(2027, 1, 1), "28000", null, 30, "200", null);
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-9999", "B737", null, null), TODAY);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, result.rawStatus());
        assertTrue(result.applicable());
        assertTrue(String.join(";", result.pendingReasons()).contains("累计飞行小时"));
    }

    // 纯日期阈值不依赖累计值：累计值缺失也不应待核实
    @Test
    void dateOnlyThresholdIgnoresMissingTotals() {
        AdRevision rev = revision(LocalDate.of(2026, 10, 4), null, null, 10, null, null);
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-9999", "B737", null, null), TODAY);
        assertEquals(AdComplianceStatus.DUE_SOON, result.rawStatus());
    }

    // 机号范围边界不完整：只填下界，本机可能落入 → 待核实（不能误判不适用）
    @Test
    void incompleteRangePendingVerify() {
        AdRevision rev = revision(LocalDate.of(2026, 11, 8), null, null, 10, null, null);
        var result = engine.evaluate(rev, List.of(scope("B737", "B-5000", null)),
                aircraft("B-6032", "B737", "24860.50", 18320), TODAY);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, result.rawStatus());
        assertTrue(result.pendingReasons().stream().anyMatch(r -> r.contains("边界不完整")));
    }

    // 边界不完整但已知边界把本机排除：明确不适用
    @Test
    void incompleteRangeButExcluded() {
        AdRevision rev = revision(LocalDate.of(2026, 11, 8), null, null, 10, null, null);
        var result = engine.evaluate(rev, List.of(scope("B737", "B-8000", null)),
                aircraft("B-6032", "B737", "24860.50", 18320), TODAY);
        assertEquals(AdComplianceStatus.NOT_APPLICABLE, result.rawStatus());
    }

    // [阈值矛盾] FH 临期窗口 ≥ 阈值 → 配置矛盾，命中飞机待核实
    @Test
    void contradictingThresholdsPendingVerify() {
        AdRevision rev = revision(LocalDate.of(2027, 1, 1), "8000", 12000, 30, "8000", 500);
        String issue = engine.checkConfig(rev);
        assertNotNull(issue);
        assertTrue(issue.contains("临期窗口"));
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-9002", "B737", "26100", 19002), TODAY);
        assertEquals(AdComplianceStatus.PENDING_VERIFY, result.rawStatus());
    }

    // 一个阈值都没配 → 配置矛盾
    @Test
    void noThresholdAtAll() {
        AdRevision rev = revision(null, null, null, null, null, null);
        assertNotNull(engine.checkConfig(rev));
    }

    // 临期窗口配了但没有对应阈值 → 矛盾
    @Test
    void warnWithoutThreshold() {
        AdRevision rev = revision(LocalDate.of(2027, 1, 1), null, null, null, "100", null);
        assertNotNull(engine.checkConfig(rev));
    }

    // 未到期：三类余量都充足
    @Test
    void notDue() {
        AdRevision rev = revision(LocalDate.of(2027, 6, 30), "40000", 30000, 30, "500", 1000);
        var result = engine.evaluate(rev, List.of(scope("B737", null, null)),
                aircraft("B-6032", "B737", "24860.50", 18320), TODAY);
        assertEquals(AdComplianceStatus.NOT_DUE, result.rawStatus());
        // 三类余量都为正，且最早限制维度必然是其中之一
        assertNotNull(result.governingKind());
        assertTrue(result.daysLeft() > 0 && result.fhLeft().doubleValue() > 0 && result.cyclesLeft() > 0);
    }
}
