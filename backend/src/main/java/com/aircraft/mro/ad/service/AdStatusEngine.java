package com.aircraft.mro.ad.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.domain.Aircraft;

/**
 * 适航指令纯判定逻辑（无副作用、不落库）：适用范围、配置矛盾、累计值缺失、
 * 三类阈值余量、最早限制维度与原始状态。
 *
 * <p>状态优先级（在“适用”的前提下）：
 * 待核实（配置/累计值/范围边界）→ 超期（任一阈值余量 ≤ 0）→ 临期（落入任一临期窗口）→ 未到期。
 * 已落实的二次有效性校验在 {@link AdComplianceCoordinator} 里做（要查工卡与证据事实）。
 */
@Component
public class AdStatusEngine {

    public static final String KIND_DATE = "DATE";
    public static final String KIND_FH = "FH";
    public static final String KIND_CYCLE = "CYCLE";

    /**
     * 范围匹配结果。
     *
     * @param applicable 明确适用
     * @param ambiguous  无法确定（边界不完整且已知边界没把本机排除）→ 待核实
     */
    public record ScopeMatch(boolean applicable, boolean ambiguous, String reason) {
    }

    /** 完整判定结果。 */
    public record EvalResult(
            ScopeMatch scope,
            List<String> pendingReasons,
            String rawStatus,
            String governingKind,
            Integer daysLeft,
            BigDecimal fhLeft,
            Integer cyclesLeft,
            String configIssue) {

        public boolean applicable() {
            return scope.applicable;
        }
    }

    // ============================== 适用范围 ==============================

    public ScopeMatch matchScope(Aircraft aircraft, List<AdScope> scopes) {
        boolean modelMentioned = false;
        boolean ambiguous = false;
        List<String> incompleteBounds = new ArrayList<>();
        for (AdScope row : scopes) {
            if (!row.getModel().equalsIgnoreCase(aircraft.getModel())) {
                continue;
            }
            modelMentioned = true;
            String lower = trimToNull(row.getTailLower());
            String upper = trimToNull(row.getTailUpper());
            String tail = aircraft.getTailNo();
            if (lower == null && upper == null) {
                // 该机型全部
                return new ScopeMatch(true, false, null);
            }
            if (lower != null && upper != null) {
                if (tail.compareTo(lower) >= 0 && tail.compareTo(upper) <= 0) {
                    return new ScopeMatch(true, false, null);
                }
                // 落在完整区间之外：本行明确不覆盖，继续看其它行
                continue;
            }
            // 只写了一边：边界不完整。已知边界能把本机排除（违反已知边界）才算明确不覆盖本行；
            // 否则无法确定本机在不在范围内 → 待核实，绝不能直接判“不适用”。
            String bound = lower != null ? lower : upper;
            boolean satisfiesKnown = lower != null ? tail.compareTo(lower) >= 0 : tail.compareTo(upper) <= 0;
            incompleteBounds.add(row.getModel() + " " + (lower != null ? "≥" + lower : "≤" + upper));
            if (satisfiesKnown) {
                ambiguous = true;
            }
        }
        if (!modelMentioned) {
            return new ScopeMatch(false, false, null);
        }
        if (ambiguous) {
            return new ScopeMatch(false, true,
                    "适用机号范围边界不完整（" + String.join("；", incompleteBounds) + "），无法确认本机是否适用");
        }
        return new ScopeMatch(false, false, null);
    }

    // ============================== 配置矛盾 ==============================

    /**
     * 实时校验阈值/临期配置。配置矛盾不阻断保存，但命中范围的飞机一律进待核实。
     */
    public String checkConfig(AdRevision rev) {
        List<String> issues = new ArrayList<>();
        boolean hasDate = rev.getDueDate() != null;
        boolean hasFh = rev.getDueFh() != null;
        boolean hasCycle = rev.getDueCycles() != null;
        if (!hasDate && !hasFh && !hasCycle) {
            issues.add("指令未设置任何截止阈值（日历日期/飞行小时/起落次数至少需要一项），无法判断期限");
        }
        if (hasFh && rev.getDueFh().compareTo(BigDecimal.ZERO) <= 0) {
            issues.add("飞行小时截止阈值必须大于 0");
        }
        if (hasCycle && rev.getDueCycles() <= 0) {
            issues.add("起落次数截止阈值必须大于 0");
        }
        if (rev.getWarnDays() != null && rev.getWarnDays() < 0) {
            issues.add("临期提前天数不能为负数");
        }
        if (rev.getWarnFh() != null && rev.getWarnFh().compareTo(BigDecimal.ZERO) < 0) {
            issues.add("临期提前飞行小时不能为负数");
        }
        if (rev.getWarnCycles() != null && rev.getWarnCycles() < 0) {
            issues.add("临期提前起落次数不能为负数");
        }
        if (!hasDate && rev.getWarnDays() != null) {
            issues.add("配置了临期提前天数 " + rev.getWarnDays() + "，但未设置日历日期阈值");
        }
        if (!hasFh && rev.getWarnFh() != null) {
            issues.add("配置了临期提前飞行小时 " + rev.getWarnFh() + "，但未设置飞行小时阈值");
        }
        if (!hasCycle && rev.getWarnCycles() != null) {
            issues.add("配置了临期提前起落次数 " + rev.getWarnCycles() + "，但未设置起落次数阈值");
        }
        // 临期窗口大于/等于阈值本身：从寿命零点起就永远在“临期”，配置不可用
        if (hasFh && rev.getWarnFh() != null && rev.getWarnFh().compareTo(rev.getDueFh()) >= 0) {
            issues.add("飞行小时临期窗口 " + rev.getWarnFh() + " 大于/等于截止阈值 " + rev.getDueFh()
                    + "（全部飞机从入役起都会落入临期，配置不可用）");
        }
        if (hasCycle && rev.getWarnCycles() != null && rev.getWarnCycles() >= rev.getDueCycles()) {
            issues.add("起落次数临期窗口 " + rev.getWarnCycles() + " 大于/等于截止阈值 " + rev.getDueCycles()
                    + "（全部飞机从入役起都会落入临期，配置不可用）");
        }
        return issues.isEmpty() ? null : String.join("；", issues);
    }

    // ============================== 完整判定 ==============================

    public EvalResult evaluate(AdRevision rev, List<AdScope> scopes, Aircraft aircraft, LocalDate today) {
        ScopeMatch scope = matchScope(aircraft, scopes);
        List<String> pending = new ArrayList<>();
        if (scope.ambiguous) {
            pending.add(scope.reason);
        }

        String liveConfigIssue = checkConfig(rev);
        String configIssue = firstNonBlank(liveConfigIssue, rev.getConfigIssue());
        // 配置矛盾是机队级事实：只要范围把本机划进来（含不确定），就待核实
        if (configIssue != null && (scope.applicable || scope.ambiguous)) {
            pending.add("指令阈值互相矛盾：" + configIssue);
        }

        // 累计值缺失：只检查“本指令实际配置了”的维度（日期维度不依赖飞机累计值）
        if (scope.applicable) {
            if (rev.getDueFh() != null && aircraft.getTotalFh() == null) {
                pending.add("缺少飞机累计飞行小时（总飞行小时未录入），无法判定飞行小时阈值");
            }
            if (rev.getDueCycles() != null && aircraft.getTotalCycles() == null) {
                pending.add("缺少飞机累计起落次数（总起落次数未录入），无法判定起落次数阈值");
            }
        }

        // 边界不完整（无法确定本机是否适用）：必须待核实，绝不能在这里被判成“不适用”
        if (scope.ambiguous) {
            Margins partial = computeMargins(rev, aircraft, today);
            return new EvalResult(scope, pending, AdComplianceStatus.PENDING_VERIFY, null,
                    partial.daysLeft, partial.fhLeft, partial.cyclesLeft, configIssue);
        }
        if (!scope.applicable) {
            return new EvalResult(scope, pending, AdComplianceStatus.NOT_APPLICABLE, null, null, null, null,
                    configIssue);
        }
        if (!pending.isEmpty()) {
            // 余量仍尽力算（能算的维度照样返回，前端阈值余量面板可见），但状态是待核实
            Margins margins = computeMargins(rev, aircraft, today);
            return new EvalResult(scope, pending, AdComplianceStatus.PENDING_VERIFY, null,
                    margins.daysLeft, margins.fhLeft, margins.cyclesLeft, configIssue);
        }

        Margins margins = computeMargins(rev, aircraft, today);
        String status;
        if (margins.overdue) {
            status = AdComplianceStatus.OVERDUE;
        } else if (margins.dueSoon) {
            status = AdComplianceStatus.DUE_SOON;
        } else {
            status = AdComplianceStatus.NOT_DUE;
        }
        return new EvalResult(scope, List.of(), status, margins.governing,
                margins.daysLeft, margins.fhLeft, margins.cyclesLeft, configIssue);
    }

    // ============================== 余量 / 最早限制 ==============================

    private record Margins(Integer daysLeft, BigDecimal fhLeft, Integer cyclesLeft,
                          boolean overdue, boolean dueSoon, String governing) {
    }

    private Margins computeMargins(AdRevision rev, Aircraft aircraft, LocalDate today) {
        Integer daysLeft = null;
        BigDecimal fhLeft = null;
        Integer cyclesLeft = null;
        boolean overdue = false;
        boolean dueSoon = false;

        if (rev.getDueDate() != null) {
            long d = ChronoUnit.DAYS.between(today, rev.getDueDate());
            daysLeft = (int) d;
            if (d <= 0) {
                overdue = true;
            }
            if (rev.getWarnDays() != null && d <= rev.getWarnDays()) {
                dueSoon = true;
            }
        }
        if (rev.getDueFh() != null && aircraft.getTotalFh() != null) {
            BigDecimal left = rev.getDueFh().subtract(aircraft.getTotalFh());
            fhLeft = left.setScale(2, java.math.RoundingMode.HALF_UP);
            if (left.compareTo(BigDecimal.ZERO) <= 0) {
                overdue = true;
            }
            if (rev.getWarnFh() != null && left.compareTo(rev.getWarnFh()) <= 0) {
                dueSoon = true;
            }
        }
        if (rev.getDueCycles() != null && aircraft.getTotalCycles() != null) {
            int left = rev.getDueCycles() - aircraft.getTotalCycles();
            cyclesLeft = left;
            if (left <= 0) {
                overdue = true;
            }
            if (rev.getWarnCycles() != null && left <= rev.getWarnCycles()) {
                dueSoon = true;
            }
        }
        return new Margins(daysLeft, fhLeft, cyclesLeft, overdue, dueSoon,
                governing(rev, aircraft, today, daysLeft, fhLeft, cyclesLeft));
    }

    /**
     * 最早限制维度：取各维度“消耗比例”最大的（已消耗/总寿命），比例最大的最先到限。
     * 日期维度以生效日→到期日为总窗口；飞行小时/起落次数直接用累计值/阈值。
     * 不可计算（缺累计值）的维度不参与，确定性且可向用户解释。
     */
    private String governing(AdRevision rev, Aircraft aircraft, LocalDate today,
                             Integer daysLeft, BigDecimal fhLeft, Integer cyclesLeft) {
        String kind = null;
        double best = -1d;
        if (rev.getDueDate() != null) {
            long span = ChronoUnit.DAYS.between(rev.getEffectiveDate(), rev.getDueDate());
            double frac;
            if (span <= 0) {
                frac = daysLeft != null && daysLeft <= 0 ? 1d : 0d;
            } else {
                long elapsed = ChronoUnit.DAYS.between(rev.getEffectiveDate(), today);
                frac = Math.max(-0.1d, elapsed / (double) span);
            }
            if (frac > best) {
                best = frac;
                kind = KIND_DATE;
            }
        }
        if (rev.getDueFh() != null && aircraft.getTotalFh() != null && rev.getDueFh().signum() > 0) {
            double frac = aircraft.getTotalFh().doubleValue() / rev.getDueFh().doubleValue();
            if (frac > best) {
                best = frac;
                kind = KIND_FH;
            }
        }
        if (rev.getDueCycles() != null && aircraft.getTotalCycles() != null && rev.getDueCycles() > 0) {
            double frac = aircraft.getTotalCycles() / (double) rev.getDueCycles();
            if (frac > best) {
                best = frac;
                kind = KIND_CYCLE;
            }
        }
        return kind;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
