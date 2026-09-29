package com.aircraft.mro.ad.service;

import java.util.Map;

/**
 * 模块七状态码与中文名。判定全部在服务端，前端只做展示。
 */
public final class AdComplianceStatus {

    private AdComplianceStatus() {
    }

    /** 不适用（机型/机号范围明确不覆盖本机） */
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";
    /** 未到期 */
    public static final String NOT_DUE = "NOT_DUE";
    /** 临期（进入指令分别配置的提前窗口） */
    public static final String DUE_SOON = "DUE_SOON";
    /** 超期（任一阈值先到） */
    public static final String OVERDUE = "OVERDUE";
    /** 待核实（累计值缺失 / 边界不完整 / 阈值矛盾 / 工卡或证据事实变化） */
    public static final String PENDING_VERIFY = "PENDING_VERIFY";
    /** 已落实（有效已关闭工卡 + 可追溯证据齐全） */
    public static final String COMPLIED = "COMPLIED";

    private static final Map<String, String> NAMES = Map.of(
            NOT_APPLICABLE, "不适用",
            NOT_DUE, "未到期",
            DUE_SOON, "临期",
            OVERDUE, "超期",
            PENDING_VERIFY, "待核实",
            COMPLIED, "已落实");

    public static String name(String code) {
        return code == null ? "" : NAMES.getOrDefault(code, code);
    }
}
