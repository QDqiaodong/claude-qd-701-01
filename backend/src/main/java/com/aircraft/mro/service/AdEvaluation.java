package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.AdRevision;

/**
 * 适航指令【单机 × 单修订版】判断引擎（纯函数，不落库）。
 *
 * <p>口径（唯一来源，页面与批量重算都走它）：
 * <ol>
 *   <li>机型不匹配 → {@link MroDict#AD_NOT_APPLICABLE}；机型匹配但机号段明确不含该机 → 不适用。</li>
 *   <li>机号段只填了一边 / 边界不是数字 → 待核实（边界不完整），不得误判为不适用。</li>
 *   <li>阈值配置矛盾（如截止日期早于生效日期）→ 待核实（阈值矛盾）。</li>
 *   <li>某维度有阈值但飞机对应累计值缺失（NULL）→ 待核实（累计值缺失）。</li>
 *   <li>日期 / 飞行小时 / 起落次数三类阈值任一先到即按最早限制执行：
 *       任一维度已超限 → 超期（按相对超限比例取最早限制维度）；
 *       否则取相对余量最小的维度，其进入预警窗口则临期，未进窗口则未到期。</li>
 *   <li>已落实（COMPLIED）由 service 在引擎结果之上结合工卡/证据复核得出，引擎不负责。</li>
 * </ol>
 */
public final class AdEvaluation {

    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private AdEvaluation() {
    }

    /** 一次判断的不可变结果 */
    public record Result(
            String status,
            boolean applicable,
            List<String> pendingReasons,
            String limitingDim,
            Integer daysToDue,
            BigDecimal fhRemaining,
            Integer cycleRemaining) {

        public String pendingReasonText() {
            return pendingReasons == null || pendingReasons.isEmpty() ? null : String.join("；", pendingReasons);
        }
    }

    /** 从机号中抽取最后一段连续数字（B-6032 → 6032，B-11099 → 11099）；抽不到返回 null。 */
    public static Long tailNumber(String tailNo) {
        if (tailNo == null) {
            return null;
        }
        Matcher matcher = DIGITS.matcher(tailNo);
        Long value = null;
        while (matcher.find()) {
            try {
                value = Long.parseLong(matcher.group());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return value;
    }

    /**
     * 评估一架飞机对一个修订版的原始状态。
     *
     * @param today 当前日期（由调用方注入，批量作业与测试口径一致）
     */
    public static Result evaluate(AdRevision rev, Aircraft aircraft, LocalDate today) {
        // 1) 机型不匹配：明确不适用
        List<String> models = splitModels(rev.getApplicableModels());
        if (!models.contains(aircraft.getModel())) {
            return notApplicable();
        }

        List<String> pending = new ArrayList<>();

        // 2) 机号段边界
        boolean hasFrom = rev.getTailRangeFrom() != null && !rev.getTailRangeFrom().isBlank();
        boolean hasTo = rev.getTailRangeTo() != null && !rev.getTailRangeTo().isBlank();
        if (hasFrom || hasTo) {
            Long from = hasFrom ? tailNumber(rev.getTailRangeFrom().trim()) : null;
            Long to = hasTo ? tailNumber(rev.getTailRangeTo().trim()) : null;
            if ((hasFrom && from == null) || (hasTo && to == null)) {
                pending.add("机号范围边界不是有效机号，无法判断适用性");
            } else {
                Long tailNo = tailNumber(aircraft.getTailNo());
                if (tailNo == null) {
                    pending.add("本机机号无法解析数字段，无法判断是否落在机号范围内");
                } else if (!hasFrom || !hasTo) {
                    // 只填一边：边界不完整，不能按开区间武断放行
                    pending.add("机号范围边界不完整（仅填写了" + (hasFrom ? "下界" : "上界") + "），无法完整判断适用性");
                } else if (from > to) {
                    pending.add("机号范围下界大于上界，范围配置矛盾");
                } else if (tailNo < from || tailNo > to) {
                    return notApplicable();
                }
            }
        }

        // 3) 阈值矛盾
        if (rev.getDueDate() != null && rev.getDueDate().isBefore(rev.getEffectiveDate())) {
            pending.add("日期截止阈值（" + rev.getDueDate() + "）早于指令生效日期（"
                    + rev.getEffectiveDate() + "），阈值互相矛盾");
        }
        if (rev.getDueFh() != null && rev.getDueFh().compareTo(BigDecimal.ZERO) < 0) {
            pending.add("飞行小时截止阈值为负数，阈值配置矛盾");
        }
        if (rev.getDueCycles() != null && rev.getDueCycles() < 0) {
            pending.add("起落次数截止阈值为负数，阈值配置矛盾");
        }
        if (!pending.isEmpty()) {
            return pending(pending, null, null, null, null);
        }

        // 4) 三维余量；任一维度"有阈值但累计值缺失" → 待核实
        Integer days = null;
        BigDecimal fhRemain = null;
        Integer cycleRemain = null;
        if (rev.getDueDate() != null) {
            days = (int) ChronoUnit.DAYS.between(today, rev.getDueDate());
        }
        if (rev.getDueFh() != null) {
            if (aircraft.getTotalFh() == null) {
                pending.add("缺少飞机总飞行小时累计值，无法判断飞行小时阈值");
            } else {
                fhRemain = rev.getDueFh().subtract(aircraft.getTotalFh());
            }
        }
        if (rev.getDueCycles() != null) {
            if (aircraft.getTotalCycles() == null) {
                pending.add("缺少飞机总起落次数累计值，无法判断起落次数阈值");
            } else {
                cycleRemain = rev.getDueCycles() - aircraft.getTotalCycles();
            }
        }
        if (!pending.isEmpty()) {
            return pending(pending, null, days, fhRemain, cycleRemain);
        }

        // 5) 一个阈值都没有 → 配置缺陷，待核实（不能被当成永久未到期）
        if (rev.getDueDate() == null && rev.getDueFh() == null && rev.getDueCycles() == null) {
            return pending(List.of("该修订版未设置任何截止阈值，无法判断到期"), null, null, null, null);
        }

        // 6) 最早限制：先看有没有超限维度；都没超限时取相对余量最小的维度判临期/未到期
        record Dim(String code, double ratio, boolean inWarn) {
        }
        List<Dim> dims = new ArrayList<>();
        if (days != null) {
            // 日期没有"阈值总量"，用 365 天做归一化尺度，只用于跨维度比较谁更紧
            double ratio = days / 365.0;
            dims.add(new Dim("DATE", ratio, days <= nz(rev.getWarnDays())));
        }
        if (fhRemain != null) {
            double threshold = rev.getDueFh().doubleValue() > 0 ? rev.getDueFh().doubleValue() : 1.0;
            BigDecimal warnFh = rev.getWarnFh() == null ? BigDecimal.ZERO : rev.getWarnFh();
            dims.add(new Dim("FH", fhRemain.doubleValue() / threshold, fhRemain.compareTo(warnFh) <= 0));
        }
        if (cycleRemain != null) {
            double threshold = rev.getDueCycles() > 0 ? rev.getDueCycles() : 1.0;
            dims.add(new Dim("CYCLE", cycleRemain / threshold, cycleRemain <= nz(rev.getWarnCycles())));
        }

        boolean anyOverdue = false;
        Dim limiting = dims.get(0);
        for (Dim d : dims) {
            if (d.ratio() < limiting.ratio()) {
                limiting = d;
            }
            if (d.ratio() < 0) {
                anyOverdue = true;
            }
        }
        String status;
        if (anyOverdue) {
            status = MroDict.AD_OVERDUE;
        } else if (limiting.inWarn()) {
            status = MroDict.AD_NEAR_DUE;
        } else {
            status = MroDict.AD_NOT_DUE;
        }
        return new Result(status, true, List.of(), limiting.code(), days, fhRemain, cycleRemain);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static List<String> splitModels(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[;,，；]")) {
            String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Result notApplicable() {
        return new Result(MroDict.AD_NOT_APPLICABLE, false, List.of(), null, null, null, null);
    }

    private static Result pending(List<String> reasons, String limiting, Integer days,
                                  BigDecimal fhRemain, Integer cycleRemain) {
        return new Result(MroDict.AD_PENDING, true, List.copyOf(reasons), limiting, days, fhRemain, cycleRemain);
    }
}
