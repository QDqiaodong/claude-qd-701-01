package com.aircraft.mro.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 机库 MRO 词典与业务规则参数：机型机位匹配、适航状态、工卡状态机、保留期限折算等。
 * 规则集中在此，改判据只改这一处。
 */
public final class MroDict {

    private MroDict() {
    }

    // ---------------- 机型与机位 ----------------
    /** 机型 → 机身全长（米），用于判断是否塞得进机位 */
    private static final Map<String, BigDecimal> MODEL_LENGTH = Map.of(
            "B737", BigDecimal.valueOf(39.5),
            "A320", BigDecimal.valueOf(37.6),
            "A330", BigDecimal.valueOf(63.7),
            "ARJ21", BigDecimal.valueOf(33.5));

    /** 机位尺寸等级 → 允许停放的机型 */
    private static final Map<String, List<String>> SIZE_FIT = Map.of(
            "A", List.of("B737", "A320", "A330", "ARJ21"),
            "B", List.of("B737", "A320", "ARJ21"),
            "C", List.of("ARJ21"));

    private static final Map<String, String> SIZE_CLASS_NAME = Map.of(
            "A", "A 类（大型机位）",
            "B", "B 类（中型机位）",
            "C", "C 类（支线机位）");

    private static final Map<String, String> BAY_STATUS = Map.of(
            "IDLE", "空闲",
            "OCCUPIED", "占用",
            "DISABLED", "停用");

    // ---------------- 适航状态 ----------------
    private static final Map<String, String> AIRWORTHY = Map.of(
            "AIRWORTHY", "适航",
            "GROUNDED", "停场待修",
            "INSPECTION", "定检中",
            "STORED", "封存");

    /** 封存机不允许开工卡 */
    public static final String AIRWORTHY_STORED = "STORED";
    /** 停场待修机不允许出位（排故未完成不得出厂） */
    public static final String AIRWORTHY_GROUNDED = "GROUNDED";

    // ---------------- 工卡 ----------------
    private static final Map<String, String> CHECK_TYPE = Map.of(
            "A_CHECK", "A检",
            "C_CHECK", "C检",
            "ROUTINE", "例行",
            "SPECIAL", "专项");

    /** 检类 → 计划工时上限（小时） */
    private static final Map<String, BigDecimal> CHECK_MAX_PLAN_HOUR = Map.of(
            "A_CHECK", BigDecimal.valueOf(24),
            "C_CHECK", BigDecimal.valueOf(120),
            "ROUTINE", BigDecimal.valueOf(16),
            "SPECIAL", BigDecimal.valueOf(48));

    private static final Map<String, String> CARD_STATUS = Map.of(
            "ASSIGNED", "待派工",
            "IN_PROGRESS", "进行中",
            "WAIT_INSP", "待检验",
            "CLOSED", "已关闭",
            "CANCELED", "已取消");

    /** 状态机：当前状态 → 允许的下一状态 */
    private static final Map<String, List<String>> CARD_NEXT = Map.of(
            "ASSIGNED", List.of("IN_PROGRESS", "CANCELED"),
            "IN_PROGRESS", List.of("WAIT_INSP", "CANCELED"),
            "WAIT_INSP", List.of("CLOSED"),
            "CLOSED", List.of(),
            "CANCELED", List.of());

    /** 单张工卡实际工时上限 */
    public static final BigDecimal MAX_ACTUAL_HOUR = BigDecimal.valueOf(200);

    // ---------------- 故障保留 ----------------
    private static final Map<String, String> LIMIT_UNIT = Map.of(
            "DAY", "飞行日",
            "HOUR", "飞行小时");

    private static final Map<String, String> DEFECT_STATUS = Map.of(
            "OPEN", "开放",
            "CLOSED", "已关闭",
            "OVERDUE", "超期");

    /** 保留期限按小时计时，按每天飞行 6 小时折算成飞行日 */
    public static final int HOUR_PER_DAY = 6;

    /** 保留期限上限（飞行日） */
    public static final int MAX_LIMIT_DAY = 120;

    // ---------------- 航材 ----------------
    private static final Map<String, String> LOAN_STATUS = Map.of(
            "OUT", "在借未还",
            "BACK", "已归还");

    // ---------------- 出厂放行审签 ----------------
    /** 草稿：发起人可修订（写保留依据），尚未进入审签链 */
    public static final String REL_DRAFT = "DRAFT";
    /** 待机械放行 */
    public static final String REL_WAIT_MECH = "WAIT_MECH";
    /** 待质量终审 */
    public static final String REL_WAIT_QA = "WAIT_QA";
    /** 已放行：质量终审通过，允许出位（出位前都算有效放行） */
    public static final String REL_RELEASED = "RELEASED";
    /** 已驳回：回到可修订状态，再次提交必须形成新的证据快照 */
    public static final String REL_REJECTED = "REJECTED";
    /** 已失效：上游事实在快照之后变化 / 终审复核不过，签字保留但不得沿用 */
    public static final String REL_INVALID = "INVALID";

    private static final Map<String, String> RELEASE_STATUS = Map.of(
            REL_DRAFT, "草稿",
            REL_WAIT_MECH, "待机械放行",
            REL_WAIT_QA, "待质量终审",
            REL_RELEASED, "已放行",
            REL_REJECTED, "已驳回",
            REL_INVALID, "已失效");

    /** 处在审签链上、上游一变就要立即失效的状态（草稿允许继续修订，不在其列） */
    public static final List<String> RELEASE_PENDING_STATUSES = List.of(REL_WAIT_MECH, REL_WAIT_QA);

    // ---------------- 模块七：适航指令落实追踪 ----------------
    /** 未到期：任一阈值都还没到临期窗口 */
    public static final String AD_NOT_DUE = "NOT_DUE";
    /** 临期：最早到限维度进入预警提前量 */
    public static final String AD_NEAR_DUE = "NEAR_DUE";
    /** 超期：最早到限维度已越过截止阈值且尚未落实 */
    public static final String AD_OVERDUE = "OVERDUE";
    /** 已落实：关联有效关闭工卡 + 有效证据，经服务端复核成立 */
    public static final String AD_COMPLIED = "COMPLIED";
    /** 待核实：累计值缺失 / 机号边界不完整 / 阈值矛盾 / 凭证失效，不得误判为不适用或已落实 */
    public static final String AD_PENDING = "PENDING";
    /** 不适用：机型或机号段明确不覆盖该飞机 */
    public static final String AD_NOT_APPLICABLE = "NOT_APPLICABLE";

    private static final Map<String, String> AD_STATUS = Map.of(
            AD_NOT_DUE, "未到期",
            AD_NEAR_DUE, "临期",
            AD_OVERDUE, "超期",
            AD_COMPLIED, "已落实",
            AD_PENDING, "待核实",
            AD_NOT_APPLICABLE, "不适用");

    /** 修订版状态 */
    private static final Map<String, String> AD_REVISION_STATUS = Map.of(
            "DRAFT", "草稿",
            "EFFECTIVE", "生效中",
            "SUPERSEDED", "已被取代");

    /** 维度 */
    private static final Map<String, String> AD_DIM = Map.of(
            "DATE", "日历日期",
            "FH", "飞行小时",
            "CYCLE", "起落次数");

    // ---------------- 取值方法 ----------------

    public static boolean isValidModel(String model) {
        return model != null && MODEL_LENGTH.containsKey(model);
    }

    public static BigDecimal modelLength(String model) {
        return MODEL_LENGTH.getOrDefault(model, BigDecimal.ZERO);
    }

    public static boolean isValidSizeClass(String sizeClass) {
        return sizeClass != null && SIZE_FIT.containsKey(sizeClass);
    }

    public static String sizeClassName(String sizeClass) {
        return sizeClass == null ? "" : SIZE_CLASS_NAME.getOrDefault(sizeClass, sizeClass);
    }

    /** 机位尺寸等级能否承载该机型 */
    public static boolean bayFits(String sizeClass, String model) {
        if (!isValidSizeClass(sizeClass) || !isValidModel(model)) {
            return false;
        }
        return SIZE_FIT.get(sizeClass).contains(model);
    }

    /** 机位可承载的机型文本（分号分隔） */
    public static String fitTypeOf(String sizeClass) {
        return isValidSizeClass(sizeClass) ? String.join(";", SIZE_FIT.get(sizeClass)) : "";
    }

    public static boolean isValidBayStatus(String status) {
        return status != null && BAY_STATUS.containsKey(status);
    }

    public static String bayStatusName(String status) {
        return status == null ? "" : BAY_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidAirworthy(String airworthy) {
        return airworthy != null && AIRWORTHY.containsKey(airworthy);
    }

    public static String airworthyName(String airworthy) {
        return airworthy == null ? "" : AIRWORTHY.getOrDefault(airworthy, airworthy);
    }

    public static boolean isValidCheckType(String checkType) {
        return checkType != null && CHECK_TYPE.containsKey(checkType);
    }

    public static String checkTypeName(String checkType) {
        return checkType == null ? "" : CHECK_TYPE.getOrDefault(checkType, checkType);
    }

    public static BigDecimal checkMaxPlanHour(String checkType) {
        return CHECK_MAX_PLAN_HOUR.getOrDefault(checkType, BigDecimal.valueOf(120));
    }

    public static boolean isValidCardStatus(String status) {
        return status != null && CARD_STATUS.containsKey(status);
    }

    public static String cardStatusName(String status) {
        return status == null ? "" : CARD_STATUS.getOrDefault(status, status);
    }

    /** 状态机流转是否允许 */
    public static boolean cardFlowAllowed(String from, String to) {
        if (!isValidCardStatus(from) || !isValidCardStatus(to)) {
            return false;
        }
        return CARD_NEXT.getOrDefault(from, List.of()).contains(to);
    }

    public static boolean isValidLimitUnit(String unit) {
        return unit != null && LIMIT_UNIT.containsKey(unit);
    }

    public static String limitUnitName(String unit) {
        return unit == null ? "" : LIMIT_UNIT.getOrDefault(unit, unit);
    }

    public static boolean isValidDefectStatus(String status) {
        return status != null && DEFECT_STATUS.containsKey(status);
    }

    public static String defectStatusName(String status) {
        return status == null ? "" : DEFECT_STATUS.getOrDefault(status, status);
    }

    public static String loanStatusName(String status) {
        return status == null ? "" : LOAN_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidReleaseStatus(String status) {
        return status != null && RELEASE_STATUS.containsKey(status);
    }

    public static String releaseStatusName(String status) {
        return status == null ? "" : RELEASE_STATUS.getOrDefault(status, status);
    }

    /** 工卡是否已闭环（已关闭 / 已取消都不再占未闭环名额） */
    public static boolean cardClosed(String status) {
        return "CLOSED".equals(status) || "CANCELED".equals(status);
    }

    /**
     * 保留期限折算成天数：DAY 直接取数值，HOUR 按每天飞行 {@value #HOUR_PER_DAY} 小时折算（向上取整）。
     */
    public static int limitToDays(String unit, Integer value) {
        int v = value == null ? 0 : value;
        if ("HOUR".equals(unit)) {
            return (int) Math.ceil(v / (double) HOUR_PER_DAY);
        }
        return v;
    }

    // ---------------- 模块七取值方法 ----------------

    public static String adStatusName(String status) {
        return status == null ? "" : AD_STATUS.getOrDefault(status, status);
    }

    public static String adRevisionStatusName(String status) {
        return status == null ? "" : AD_REVISION_STATUS.getOrDefault(status, status);
    }

    public static String adDimName(String dim) {
        return dim == null ? "" : AD_DIM.getOrDefault(dim, dim);
    }
}
