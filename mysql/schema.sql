-- ============================================================================
-- 飞机维修机库（MRO）管理系统 · 建表 + 种子数据
-- 库名 aircraft_mro，由 mysql 镜像的 docker-entrypoint 在【镜像构建期】执行一次。
--
-- 种子数据保证下面每条业务规则都能被真的触发（不是"理论上可以"）：
--   [S] @Subselect 只读汇总视图 v_work_card_summary 由 work_card 实时聚合而来，
--       飞机台账列表的工卡汇总列全部来自它（飞机列表页必须与它联查）。
--   [R1.1] 机位编号唯一        → BAY-A01 已存在
--   [R1.2] 机位尺寸等级门禁    → C 类机位只能停支线机（ARJ21），A 类可停全部机型
--   [R1.3] 停用机位禁入位      → BAY-A05 = DISABLED
--   [R1.4] 一个机位只停一架    → BAY-A01 已占用，再入位会被拦
--   [R1.5] 一架飞机只占一个位  → B-6032 已在 BAY-A01，换个机位入位会被拦
--   [R2.1] 停场待修机禁出厂    → B-6571 = GROUNDED
--   [R2.2] 封存机不可开工卡    → B-2819 = STORED
--   [R3.1] 工卡状态机顺序      → 待派工 → 进行中 → 待检验 → 已关闭
--   [R3.2] 已关闭工卡不可再改  → WC-2026-0004 = CLOSED
--   [R3.3] 已取消工卡不可再流转 → WC-2026-0007 = CANCELED
--   [R3.4] 完工必须早于当前且实测工时为 0 不让完工 → service 校验
--   [R4.1] 缺陷到期日折算      → DAY 按飞行日、HOUR 按日飞 6 小时折算
--   [R4.2] 超期缺陷整行标红    → DF-2026-0003 / DF-2026-0006 = OVERDUE
--   [R4.3] 已关闭缺陷不可再关  → DF-2026-0004 = CLOSED
--   [R5.1] 低库存预警          → PN-3200-004 库存 3 低于最低 8
--   [R5.2] 库存不足禁借出      → PN-7310-002 库存 1
--   [R5.3] 未归还不可重复还    → TL-2026-0003 已归还
--   [R5.4] 归还数量不可大于借出 → service 校验
--
-- 模块六（出厂放行审签）验收示例数据（可直接点完五条验收路径）：
--   [REL-1] 正常三人审签：B-9001（13 号机，BAY-C05）适航、工卡全闭环、无故障 → RO-2026-0001 草稿
--   [REL-2] 开放故障带依据放行：B-9002（14 号机，BAY-B05）适航、工卡全闭环、1 条未超期开放故障 → RO-2026-0002 草稿
--   [REL-3] 审签中工卡状态变化导致失效：RO-2026-0003（B-9003=15 号机，BAY-B06）已失效，
--           原因 = 快照之后新开工卡 WC-2026-0108（待派工）
--   [REL-4] 两人同时终审：RO-2026-0004（B-9004=16 号机，BAY-C06）处于待质量终审，已机械放行，
--           可用两个不同终审人同时点"终审通过"验收冲突（只有一个成功）
--   [REL-5] 放行后才可出位：RO-2026-0005（B-9005=17 号机，BAY-A06）已放行未出位，
--           机位"安排出位"成功；B-9006（18 号机，BAY-C07）没有任何放行，出位被统一阻断
--
-- 模块七（适航指令落实追踪 AD）验收示例：
--   [AD-01] 日期先到期：CAD-2026-001 R1（B737 全系，日期阈值 20 天后、提前 30 天预警）
--   [AD-02] 飞行小时先到期：CAD-2025-014 R1（B737 全系，FH 25000 先于起落 30000 到限）
--   [AD-03] 起落次数先到期：CAD-2026-007 R1（B737 全系，CYC 18500 先于 FH 33000 到限）
--   [AD-04] 日期先到期 + 已落实：CAD-2026-004 R1（A320，B-7325 凭已关闭工卡 WC-2026-0201 落实）
--   [AD-05] 范围不适用：CAD-2026-005 R0 仅 B-11001~B-11099 段 ARJ21，其余机号不适用
--   [AD-06] 累计值缺失：B-11099（21 号机）总 FH/总起落未录入（NULL）→ 待核实
--   [AD-07] 阈值互相矛盾：CAD-2026-008 R0 截止日期早于生效日期 → 待核实
--   [AD-08] 机号范围边界不完整：CAD-2026-009 R0 只填了上界/下界 → 待核实
--   [AD-09] 历史版本保留：CAD-2026-011 存在 R0（已被 R1 取代），R0 的判断与证据冻结留存
--   [AD-10] 工卡改挂：CAD-2026-010 R1，B-6571 的落实工卡 WC-2026-0203 已改挂 B-7411 → 回待核实
--   [AD-11] 工卡取消/证据作废：CAD-2026-020 R1 工卡 WC-2026-0204 已取消；
--           CAD-2026-001 R1 的 B-6032 落实证据已登记作废 → 均回待核实（关联不删除）
--   [AD-12] 大批量重算：机队 21 架，支持按指令创建重算作业，可注入部分失败并对失败项续跑
-- ============================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

CREATE DATABASE IF NOT EXISTS aircraft_mro DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE aircraft_mro;

DROP TABLE IF EXISTS ad_recalc_job_item;
DROP TABLE IF EXISTS ad_recalc_job;
DROP TABLE IF EXISTS ad_event;
DROP TABLE IF EXISTS ad_evidence;
DROP TABLE IF EXISTS ad_compliance_link;
DROP TABLE IF EXISTS ad_compliance;
DROP TABLE IF EXISTS ad_revision;
DROP TABLE IF EXISTS ad_directive;
DROP TABLE IF EXISTS release_event;
DROP TABLE IF EXISTS release_snapshot_defect;
DROP TABLE IF EXISTS release_snapshot_card;
DROP TABLE IF EXISTS release_snapshot;
DROP TABLE IF EXISTS release_order;
DROP TABLE IF EXISTS tool_loan;
DROP TABLE IF EXISTS part;
DROP TABLE IF EXISTS defect;
DROP TABLE IF EXISTS work_card;
DROP TABLE IF EXISTS aircraft;
DROP TABLE IF EXISTS bay;

-- ----------------------------------------------------------------------------
-- 模块一：机库机位
-- ----------------------------------------------------------------------------
CREATE TABLE bay (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    bay_no              VARCHAR(16)  NOT NULL COMMENT '机位编号',
    size_class          VARCHAR(4)   NOT NULL COMMENT '尺寸等级：A 类 / B 类 / C 类',
    fit_type            VARCHAR(64)  NOT NULL COMMENT '可承载机型，分号分隔',
    length_m            DECIMAL(6, 1) NOT NULL DEFAULT 0 COMMENT '机位长度（米）',
    status              VARCHAR(16)  NOT NULL COMMENT '状态：IDLE 空闲 / OCCUPIED 占用 / DISABLED 停用',
    current_aircraft_id BIGINT       NULL COMMENT '当前占用飞机 id',
    updated_at          DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bay_no (bay_no),
    KEY idx_bay_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '机库机位';

-- [R1.1]~[R1.5] A01 已被 B-6032 占用；A05 停用；C 类只能停 ARJ21
INSERT INTO bay (id, bay_no, size_class, fit_type, length_m, status, current_aircraft_id, updated_at) VALUES
    (1,  'BAY-A01', 'A', 'B737;A320;A330;ARJ21', 62.0, 'OCCUPIED', 1,  DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (2,  'BAY-A02', 'A', 'B737;A320;A330;ARJ21', 62.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (3,  'BAY-A03', 'A', 'B737;A320;A330;ARJ21', 58.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 9 DAY)),
    (4,  'BAY-B01', 'B', 'B737;A320;ARJ21',      44.0, 'OCCUPIED', 3,  DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (5,  'BAY-B02', 'B', 'B737;A320;ARJ21',      44.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (6,  'BAY-B03', 'B', 'B737;A320;ARJ21',      42.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (7,  'BAY-B04', 'B', 'B737;A320;ARJ21',      42.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 11 DAY)),
    (8,  'BAY-C01', 'C', 'ARJ21',                34.0, 'OCCUPIED', 5,  DATE_SUB(NOW(), INTERVAL 5 DAY)),
    (9,  'BAY-C02', 'C', 'ARJ21',                34.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 7 DAY)),
    (10, 'BAY-C03', 'C', 'ARJ21',                32.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 8 DAY)),
    (11, 'BAY-C04', 'C', 'ARJ21',                32.0, 'IDLE',     NULL, DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (12, 'BAY-A05', 'A', 'B737;A320;A330;ARJ21', 60.0, 'DISABLED', NULL, DATE_SUB(NOW(), INTERVAL 40 DAY)),
    -- 模块六演示机位：均已占用（B-9001 ~ B-9006）
    (13, 'BAY-B05', 'B', 'B737;A320;ARJ21',      44.0, 'OCCUPIED', 14, DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (14, 'BAY-B06', 'B', 'B737;A320;ARJ21',      44.0, 'OCCUPIED', 15, DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (15, 'BAY-A06', 'A', 'B737;A320;A330;ARJ21', 62.0, 'OCCUPIED', 17, DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (16, 'BAY-C05', 'C', 'ARJ21',                34.0, 'OCCUPIED', 13, DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (17, 'BAY-C06', 'C', 'ARJ21',                34.0, 'OCCUPIED', 16, DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (18, 'BAY-C07', 'C', 'ARJ21',                34.0, 'OCCUPIED', 18, DATE_SUB(NOW(), INTERVAL 2 DAY));

-- ----------------------------------------------------------------------------
-- 模块二：飞机台账
-- ----------------------------------------------------------------------------
CREATE TABLE aircraft (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tail_no         VARCHAR(16)  NOT NULL COMMENT '机号（注册号）',
    model           VARCHAR(16)  NOT NULL COMMENT '机型：B737 / A320 / A330 / ARJ21',
    airline         VARCHAR(64)  NOT NULL COMMENT '所属航司',
    total_fh        DECIMAL(12, 2) NULL COMMENT '总飞行小时，NULL=累计值尚未录入（适航指令进入待核实）',
    total_cycles    INT           NULL COMMENT '总起落次数，NULL=累计值尚未录入（适航指令进入待核实）',
    airworthy       VARCHAR(16)  NOT NULL COMMENT '适航状态：AIRWORTHY 适航 / GROUNDED 停场待修 / INSPECTION 定检中 / STORED 封存',
    note            VARCHAR(128) NULL COMMENT '备注',
    updated_at      DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tail_no (tail_no),
    KEY idx_aircraft_model (model),
    KEY idx_aircraft_airworthy (airworthy)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '飞机台账';

-- [R2.1] 4 号机停场待修；[R2.2] 7 号机封存
INSERT INTO aircraft (id, tail_no, model, airline, total_fh, total_cycles, airworthy, note, updated_at) VALUES
    (1,  'B-6032', 'B737',  '华夏长风航空', 24860.50, 18320, 'INSPECTION', 'A 检第 3 段',        DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (2,  'B-6108', 'A320',  '华夏长风航空', 19742.30, 14015, 'AIRWORTHY',  '正常航班运营',        DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (3,  'B-6571', 'A330',  '东方云途航空', 41205.80,  9230, 'GROUNDED',   '左发滑油压力波动',    DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (4,  'B-6688', 'B737',  '东方云途航空', 30118.90, 21760, 'AIRWORTHY',  '正常航班运营',        DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (5,  'B-2819', 'ARJ21', '北疆支线航空',  9860.20, 10240, 'STORED',     '长期封存，客改货待批', DATE_SUB(NOW(), INTERVAL 30 DAY)),
    (6,  'B-3027', 'ARJ21', '北疆支线航空', 11234.60, 12580, 'AIRWORTHY',  '正常航班运营',        DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (7,  'B-7325', 'A320',  '南岭快线航空', 15903.40, 11870, 'AIRWORTHY',  '正常航班运营',        DATE_SUB(NOW(), INTERVAL 5 DAY)),
    (8,  'B-7411', 'A330',  '南岭快线航空', 52380.10, 11020, 'INSPECTION', 'C 检中，客舱整新',    DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (9,  'B-5206', 'B737',  '华夏长风航空', 27655.70, 19940, 'GROUNDED',   '前起落架渗漏待件',    DATE_SUB(NOW(), INTERVAL 7 DAY)),
    (10, 'B-5590', 'A320',  '东方云途航空', 22018.40, 16230, 'AIRWORTHY',  '正常航班运营',        DATE_SUB(NOW(), INTERVAL 8 DAY)),
    (11, 'B-3305', 'ARJ21', '北疆支线航空',  7342.90,  8910, 'AIRWORTHY',  '高原航线改装完毕',    DATE_SUB(NOW(), INTERVAL 9 DAY)),
    (12, 'B-8062', 'B737',  '南岭快线航空', 33490.20, 24110, 'INSPECTION', '例行周检',           DATE_SUB(NOW(), INTERVAL 10 DAY)),
    -- 模块六：13 正常三人审签 / 14 带开放故障放行 / 15 工卡变化失效 / 16 两人同时终审 / 17 已放行可出位 / 18 无放行阻断
    (13, 'B-9001', 'ARJ21', '北疆支线航空', 12050.00, 13010, 'AIRWORTHY',  'A 检完工，待出厂放行', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (14, 'B-9002', 'B737',  '华夏长风航空', 26100.00, 19002, 'AIRWORTHY',  '定检完工，1 条 MEL 保留待终审', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (15, 'B-9003', 'B737',  '东方云途航空', 31050.00, 22010, 'AIRWORTHY',  '定检完工，待出厂放行', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (16, 'B-9004', 'ARJ21', '南岭快线航空',  8820.00,  9300, 'AIRWORTHY',  '周检完工，待质量终审', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (17, 'B-9005', 'A320',  '华夏长风航空', 20310.00, 14880, 'AIRWORTHY',  '终审已放行，等待出位', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (18, 'B-9006', 'ARJ21', '北疆支线航空',  6500.00,  7120, 'AIRWORTHY',  '定检完工（尚未发起放行）', DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 模块七：19/20 号机落在机号段 B-11001~B-11099（AD-005 适用）；
--         21 号机是新机交付，总飞行小时/总起落次数尚未录入（NULL）→ 任何累计阈值都判「待核实」
INSERT INTO aircraft (id, tail_no, model, airline, total_fh, total_cycles, airworthy, note, updated_at) VALUES
    (19, 'B-11001', 'ARJ21', '北疆支线航空',  5400.00,  6100, 'AIRWORTHY', '高原支线运营',          DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (20, 'B-11030', 'ARJ21', '北疆支线航空',  7800.00,  8450, 'AIRWORTHY', '北疆快线运营',          DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (21, 'B-11099', 'ARJ21', '北疆支线航空',  NULL,     NULL, 'AIRWORTHY', '新机交付，累计值待录入', DATE_SUB(NOW(), INTERVAL 1 DAY));

-- ----------------------------------------------------------------------------
-- 模块三：定检工卡
-- ----------------------------------------------------------------------------
CREATE TABLE work_card (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    card_no      VARCHAR(32)  NOT NULL COMMENT '工卡号',
    aircraft_id  BIGINT       NOT NULL COMMENT '飞机 id',
    check_type   VARCHAR(16)  NOT NULL COMMENT '检类：A_CHECK A检 / C_CHECK C检 / ROUTINE 例行 / SPECIAL 专项',
    title        VARCHAR(128) NOT NULL COMMENT '工作内容',
    planned_hour DECIMAL(8, 2) NOT NULL DEFAULT 0 COMMENT '计划工时',
    actual_hour  DECIMAL(8, 2) NOT NULL DEFAULT 0 COMMENT '实际工时',
    start_time   DATETIME     NULL COMMENT '开工时间',
    end_time     DATETIME     NULL COMMENT '完工时间',
    status       VARCHAR(16)  NOT NULL COMMENT '状态：ASSIGNED 待派工 / IN_PROGRESS 进行中 / WAIT_INSP 待检验 / CLOSED 已关闭 / CANCELED 已取消',
    executor     VARCHAR(32)  NULL COMMENT '执行人（机械员）',
    inspector    VARCHAR(32)  NULL COMMENT '检验员',
    updated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_no (card_no),
    KEY idx_card_aircraft (aircraft_id),
    KEY idx_card_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '定检工卡';

-- [R3.1]~[R3.3] 覆盖整条状态机：1 号机 B-6032 上正好有一条完整闭环 + 一条已取消
INSERT INTO work_card (id, card_no, aircraft_id, check_type, title, planned_hour, actual_hour, start_time, end_time, status, executor, inspector, updated_at) VALUES
    (1,  'WC-2026-0001', 1, 'A_CHECK',  '左发滑油滤更换',        6.00,  6.50, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 'CLOSED',      '陈志强', '罗建军', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (2,  'WC-2026-0002', 1, 'ROUTINE',  '客舱座椅力矩复查',      4.00,  0.00, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, 'IN_PROGRESS', '陈志强', NULL,     DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (3,  'WC-2026-0003', 1, 'ROUTINE',  '货舱地板封严检查',      3.50,  3.20, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'WAIT_INSP',   '王大鹏', NULL,     DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (4,  'WC-2026-0004', 1, 'SPECIAL',  'TCAS 天线加装',         8.00,  9.00, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY), 'CLOSED',      '刘振华', '罗建军', DATE_SUB(NOW(), INTERVAL 7 DAY)),
    (5,  'WC-2026-0005', 2, 'ROUTINE',  '液压系统外部渗漏检查',  2.50,  0.00, NULL, NULL, 'ASSIGNED',    NULL,     NULL,     DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (6,  'WC-2026-0006', 3, 'SPECIAL',  '左发滑油压力传感器排故', 12.00, 0.00, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, 'IN_PROGRESS', '孙立伟', NULL,     DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (7,  'WC-2026-0007', 3, 'ROUTINE',  '电瓶容量测试',          2.00,  0.00, NULL, NULL, 'CANCELED',    NULL,     NULL,     DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (8,  'WC-2026-0008', 4, 'C_CHECK',  'C 检第 1 段结构检查',    48.00, 0.00, NULL, NULL, 'ASSIGNED',    NULL,     NULL,     DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (9,  'WC-2026-0009', 5, 'ROUTINE',  '封存机每周通风检查',    1.50,  1.50, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), 'CLOSED',      '赵岩',   '罗建军', DATE_SUB(NOW(), INTERVAL 5 DAY)),
    (10, 'WC-2026-0010', 6, 'A_CHECK',  '客门滑梯气瓶压力检查',  5.00,  0.00, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, 'IN_PROGRESS', '钱明亮', NULL,     DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (11, 'WC-2026-0011', 7, 'ROUTINE',  '前轮轴承润滑',          3.00,  2.80, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), 'WAIT_INSP',   '陈志强', NULL,     DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (12, 'WC-2026-0012', 8, 'C_CHECK',  '客舱整新内饰拆装',      36.00, 0.00, DATE_SUB(NOW(), INTERVAL 4 DAY), NULL, 'IN_PROGRESS', '孙立伟', NULL,     DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (13, 'WC-2026-0013', 8, 'SPECIAL',  '翼尖小翼裂纹修理',      16.00, 17.50, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), 'CLOSED',    '刘振华', '周国平', DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (14, 'WC-2026-0014', 9, 'SPECIAL',  '前起落架渗漏排故',      10.00, 0.00, DATE_SUB(NOW(), INTERVAL 6 DAY), NULL, 'WAIT_INSP',   '王大鹏', NULL,     DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (15, 'WC-2026-0015', 12,'ROUTINE',  '周检：操纵面行程校装',  4.50,  0.00, NULL, NULL, 'ASSIGNED',    NULL,     NULL,     DATE_SUB(NOW(), INTERVAL 1 DAY));

-- 模块六演示工卡：
--   B-9001(13)、B-9002(14)、B-9003(15)、B-9004(16)、B-9005(17)、B-9006(18) 维修工卡均已闭环；
--   WC-2026-0108 是 B-9003 快照【之后】新开的未闭环工卡，用于演示审签立即失效。
INSERT INTO work_card (id, card_no, aircraft_id, check_type, title, planned_hour, actual_hour, start_time, end_time, status, executor, inspector, updated_at) VALUES
    (16, 'WC-2026-0101', 13, 'A_CHECK',  'ARJ21 A 检：发动机滑油滤检查', 5.00,  5.20, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'CLOSED', '孙立伟', '罗建军', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (17, 'WC-2026-0102', 13, 'ROUTINE',  '起落架舱门收放检查',          3.00,  2.90, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 'CLOSED', '钱明亮', '罗建军', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (18, 'WC-2026-0103', 14, 'A_CHECK',  '左发孔探检查',                7.00,  7.40, DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'CLOSED', '陈志强', '周国平', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (19, 'WC-2026-0104', 14, 'ROUTINE',  '货舱烟雾探测系统测试',        2.50,  2.50, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 'CLOSED', '王大鹏', '周国平', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (20, 'WC-2026-0105', 15, 'A_CHECK',  '液压油滤更换',                4.00,  4.10, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'CLOSED', '孙立伟', '罗建军', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (21, 'WC-2026-0106', 16, 'ROUTINE',  '应急滑梯压力检查',            3.00,  3.00, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 1 DAY), 'CLOSED', '赵岩',   '周国平', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (22, 'WC-2026-0107', 17, 'A_CHECK',  'A320 A 检：飞控舵面检查',     6.00,  6.30, DATE_SUB(NOW(), INTERVAL 4 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY), 'CLOSED', '陈志强', '罗建军', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (24, 'WC-2026-0109', 18, 'ROUTINE',  '导航数据库版本核对',          1.50,  1.50, DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), 'CLOSED', '钱明亮', '周国平', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    -- 这张是 B-9003 放行快照之后新增的未闭环工卡（验收 [REL-3]：审签中工卡状态变化导致失效）
    (23, 'WC-2026-0108', 15, 'SPECIAL',  '快照后追加：客舱 PA 放大器排故', 3.00, 0.00, NULL, NULL, 'ASSIGNED', NULL, NULL, DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- 模块七：适航指令落实工卡
--   0201：B-7325(7) 已关闭，CAD-2026-004 R1 的有效落实凭证（已落实示例）
--   0202：关闭后又被重新打开（当前待检验），CAD-2026-005 R0 历史版本的落实凭证，在 R0 被取代时已冻结
--   0203：原本挂在 B-6571(3) 名下作为 CAD-2026-010 R1 落实工卡，后改挂 B-7411(8)（已关闭）→ 原落实回待核实
--   0204：已取消工卡，CAD-2026-020 R1 历史曾关联 → 落实回待核实
INSERT INTO work_card (id, card_no, aircraft_id, check_type, title, planned_hour, actual_hour, start_time, end_time, status, executor, inspector, updated_at) VALUES
    (25, 'WC-2026-0201', 7,  'SPECIAL', 'AD 落实：B-7325 A320 升降舵铰链检查', 6.00, 6.20, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 11 DAY), 'CLOSED',    '陈志强', '罗建军', DATE_SUB(NOW(), INTERVAL 11 DAY)),
    (26, 'WC-2026-0202', 20, 'SPECIAL', 'AD 落实（历史 R0）：B-11030 缝翼作动器检查', 4.00, 4.10, DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 26 DAY), 'WAIT_INSP', '孙立伟', NULL, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
    (27, 'WC-2026-0203', 8,  'SPECIAL', 'AD 落实：货舱火警探测器改装（原派 B-6571，改挂 B-7411）', 10.00, 10.50, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 7 DAY), 'CLOSED', '刘振华', '周国平', DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (28, 'WC-2026-0204', 3,  'SPECIAL', 'AD 落实（已取消）：左发防冰管路检查', 3.50, 0.00, DATE_SUB(NOW(), INTERVAL 5 DAY), NULL, 'CANCELED', '王大鹏', NULL, DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ----------------------------------------------------------------------------
-- 模块四：故障保留
-- ----------------------------------------------------------------------------
CREATE TABLE defect (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    defect_no    VARCHAR(32) NOT NULL COMMENT '缺陷单号',
    aircraft_id  BIGINT      NOT NULL COMMENT '飞机 id',
    ata_chapter  VARCHAR(16) NOT NULL COMMENT 'ATA 章节',
    description  VARCHAR(255) NOT NULL COMMENT '故障描述',
    limit_unit   VARCHAR(8)  NOT NULL COMMENT '保留期限单位：DAY 飞行日 / HOUR 飞行小时',
    limit_value  INT         NOT NULL DEFAULT 0 COMMENT '保留期限数值',
    found_date   DATE        NOT NULL COMMENT '发现日期',
    due_date     DATE        NOT NULL COMMENT '保留到期日（DAY 按日加，HOUR 按日飞 6 小时折算）',
    status       VARCHAR(16) NOT NULL COMMENT '状态：OPEN 开放 / CLOSED 已关闭 / OVERDUE 超期',
    reporter     VARCHAR(32) NULL COMMENT '填报人',
    updated_at   DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_defect_no (defect_no),
    KEY idx_defect_aircraft (aircraft_id),
    KEY idx_defect_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '故障保留';

-- [R4.1]~[R4.3] 0003 / 0006 已超期；0007 再过 2 天到期（临界演示）；0004 已关闭
INSERT INTO defect (id, defect_no, aircraft_id, ata_chapter, description, limit_unit, limit_value, found_date, due_date, status, reporter, updated_at) VALUES
    (1,  'DF-2026-0001', 1,  '32-11', '右主起落架刹车磨损指示器接近极限', 'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 2 DAY),  DATE_ADD(CURDATE(), INTERVAL 8 DAY),  'OPEN',    '机长李航',   DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (2,  'DF-2026-0002', 1,  '21-31', '后货舱增压区一隔板裂纹（按 MEL 保留）', 'HOUR', 60, DATE_SUB(CURDATE(), INTERVAL 4 DAY),  DATE_ADD(CURDATE(), INTERVAL 6 DAY),  'OPEN',    '机械员陈志强', DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (3,  'DF-2026-0003', 1,  '24-21', 'APU 发电机输出电压波动超限',      'DAY',  5,  DATE_SUB(CURDATE(), INTERVAL 9 DAY),  DATE_SUB(CURDATE(), INTERVAL 4 DAY),  'OVERDUE', '机长李航',   DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (4,  'DF-2026-0004', 2,  '34-12', '气象雷达在暴雨模式下回波衰减',    'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'CLOSED',  '机长周涛',   DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (5,  'DF-2026-0005', 3,  '72-31', '左发滑油压力偶发下降（排故中）',   'DAY',  3,  DATE_SUB(CURDATE(), INTERVAL 1 DAY),  DATE_ADD(CURDATE(), INTERVAL 2 DAY),  'OPEN',    '机长王子恒', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (6,  'DF-2026-0006', 3,  '28-11', '中央油箱油量指示跳变',           'HOUR', 30, DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_SUB(CURDATE(), INTERVAL 7 DAY),  'OVERDUE', '机务孙立伟', DATE_SUB(NOW(), INTERVAL 7 DAY)),
    (7,  'DF-2026-0007', 4,  '25-31', '3L 座椅靠背调节失效',           'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 8 DAY),  DATE_ADD(CURDATE(), INTERVAL 2 DAY),  'OPEN',    '乘务长何蕊', DATE_SUB(NOW(), INTERVAL 8 DAY)),
    (8,  'DF-2026-0008', 5,  '33-43', '左航行灯灯罩龟裂',             'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_SUB(CURDATE(), INTERVAL 20 DAY), 'CLOSED',  '机务赵岩',   DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (9,  'DF-2026-0009', 6,  '49-11', 'APU 引气压力低于手册下限',       'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 3 DAY),  DATE_ADD(CURDATE(), INTERVAL 7 DAY),  'OPEN',    '机务钱明亮', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (10, 'DF-2026-0010', 7,  '27-51', '2 号扰流板作动筒渗漏',          'HOUR', 48, DATE_SUB(CURDATE(), INTERVAL 5 DAY),  DATE_ADD(CURDATE(), INTERVAL 3 DAY),  'OPEN',    '机务陈志强', DATE_SUB(NOW(), INTERVAL 5 DAY)),
    (11, 'DF-2026-0011', 8,  '53-11', '机身蒙皮一处凹坑待测厚',         'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 6 DAY),  DATE_ADD(CURDATE(), INTERVAL 4 DAY),  'OPEN',    '机务刘振华', DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (12, 'DF-2026-0012', 9,  '32-31', '前起落架减震支柱渗漏',           'DAY',  3,  DATE_SUB(CURDATE(), INTERVAL 6 DAY),  DATE_SUB(CURDATE(), INTERVAL 3 DAY),  'OVERDUE', '机务王大鹏', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (13, 'DF-2026-0013', 12, '31-41', '风挡雨刷低速档失效',            'DAY',  10, DATE_SUB(CURDATE(), INTERVAL 1 DAY),  DATE_ADD(CURDATE(), INTERVAL 9 DAY),  'OPEN',    '机长李航',   DATE_SUB(NOW(), INTERVAL 1 DAY));

-- 模块六：B-9002（14 号机）有 1 条【未超期】开放故障，验收"开放故障带保留依据放行"。
INSERT INTO defect (id, defect_no, aircraft_id, ata_chapter, description, limit_unit, limit_value, found_date, due_date, status, reporter, updated_at) VALUES
    (14, 'DF-2026-0014', 14, '36-12', '1 号客舱空调组件温度调节偏慢（按 MEL 21-2 保留）', 'DAY', 10,
     DATE_SUB(CURDATE(), INTERVAL 2 DAY), DATE_ADD(CURDATE(), INTERVAL 8 DAY), 'OPEN', '机械员陈志强', DATE_SUB(NOW(), INTERVAL 2 DAY));

-- ----------------------------------------------------------------------------
-- 模块五：航材
-- ----------------------------------------------------------------------------
CREATE TABLE part (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    part_no    VARCHAR(32)  NOT NULL COMMENT '件号',
    name       VARCHAR(64)  NOT NULL COMMENT '名称',
    spec       VARCHAR(64)  NOT NULL COMMENT '规格',
    unit       VARCHAR(8)   NOT NULL DEFAULT '件' COMMENT '单位',
    stock_qty  INT          NOT NULL DEFAULT 0 COMMENT '库存数量',
    min_qty    INT          NOT NULL DEFAULT 0 COMMENT '最低库存',
    unit_price DECIMAL(10, 2) NOT NULL DEFAULT 0 COMMENT '单价（元）',
    location   VARCHAR(32)  NULL COMMENT '货架库位',
    updated_at DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_part_no (part_no),
    KEY idx_part_stock (stock_qty)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '航材件';

-- [R5.1][R5.2] 004 库存 3 低于最低 8；002 库存仅 1；008 库存 0
INSERT INTO part (id, part_no, name, spec, unit, stock_qty, min_qty, unit_price, location, updated_at) VALUES
    (1,  'PN-3200-001', '主轮轮胎',      'H44.5x16.5-21',  '件', 24,   6,  18600.00, 'H-A-01', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (2,  'PN-3200-002', '前轮轮胎',      '27x7.75-15',     '件', 16,   4,   9300.00, 'H-A-02', DATE_SUB(NOW(), INTERVAL 3 DAY)),
    (3,  'PN-3200-003', '刹车组件',      'B737-800 主起',  '套',  9,   3,  76000.00, 'H-A-05', DATE_SUB(NOW(), INTERVAL 5 DAY)),
    (4,  'PN-3200-004', '刹车片（黄铜）', '进口-west2',     '片',  3,   8,   1280.00, 'H-A-07', DATE_SUB(NOW(), INTERVAL 1 DAY)),
    (5,  'PN-7310-001', '发动机滑油',    'MOBIL JET II',   '桶', 42,  10,   2100.00, 'H-B-01', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (6,  'PN-7310-002', '滑油滤',        'P/N 2189700-3',  '件',  1,   4,   5600.00, 'H-B-04', DATE_SUB(NOW(), INTERVAL 2 DAY)),
    (7,  'PN-7310-003', '燃油滤',        'P/N 3215600-1',  '件', 12,   4,   4300.00, 'H-B-05', DATE_SUB(NOW(), INTERVAL 4 DAY)),
    (8,  'PN-2400-001', 'APU 起动电机',  'GTCP 131-9B',    '台',  2,   2, 148000.00, 'H-C-02', DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (9,  'PN-2400-002', 'APU 燃油调节器', 'PN 3886200-4',   '件',  0,   2,  96000.00, 'H-C-03', DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (10, 'PN-3300-001', '航行灯灯泡',    '28V/250W',       '只', 60,  15,    320.00, 'H-D-01', DATE_SUB(NOW(), INTERVAL 7 DAY)),
    (11, 'PN-3300-002', '风挡雨刷组件',  'A330 主风挡',    '套',  4,   2,  12800.00, 'H-D-04', DATE_SUB(NOW(), INTERVAL 9 DAY)),
    (12, 'PN-5700-001', '客椅安全带',    '三点式/灰色',    '条', 38,  12,    680.00, 'H-E-01', DATE_SUB(NOW(), INTERVAL 11 DAY)),
    (13, 'PN-5700-002', '客椅座垫套',    '阻燃羊毛混纺',   '件', 22,  10,    460.00, 'H-E-03', DATE_SUB(NOW(), INTERVAL 12 DAY)),
    (14, 'PN-2800-001', '中央油箱油泵',  'PN 4213800-2',   '台',  5,   2,  88000.00, 'H-B-09', DATE_SUB(NOW(), INTERVAL 15 DAY));

-- ----------------------------------------------------------------------------
-- 模块五：航材借出/归还
-- ----------------------------------------------------------------------------
CREATE TABLE tool_loan (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    loan_no     VARCHAR(32) NOT NULL COMMENT '借还单号',
    part_id     BIGINT      NOT NULL COMMENT '航材件 id',
    borrower    VARCHAR(32) NOT NULL COMMENT '领用人',
    purpose     VARCHAR(64) NULL COMMENT '用途（关联工卡 / 飞机）',
    quantity    INT         NOT NULL DEFAULT 1 COMMENT '借出数量',
    loan_time   DATETIME    NOT NULL COMMENT '借出时间',
    return_time DATETIME    NULL COMMENT '归还时间，NULL 表示在借未还',
    handler     VARCHAR(32) NULL COMMENT '库管经办人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_loan_no (loan_no),
    KEY idx_loan_part (part_id),
    KEY idx_loan_return (return_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '航材借还记录';

-- [R5.3] 0001/0002/0004 未归还；0003/0005/0006 已归还
INSERT INTO tool_loan (id, loan_no, part_id, borrower, purpose, quantity, loan_time, return_time, handler) VALUES
    (1,  'TL-2026-0001', 3,  '陈志强', 'WC-2026-0001 A 检',  2, DATE_SUB(NOW(), INTERVAL 3 DAY), NULL, '库管吴敏'),
    (2,  'TL-2026-0002', 4,  '王大鹏', 'WC-2026-0003 检查',  3, DATE_SUB(NOW(), INTERVAL 2 DAY), NULL, '库管吴敏'),
    (3,  'TL-2026-0003', 1,  '赵岩',   'WC-2026-0009 封存',  2, DATE_SUB(NOW(), INTERVAL 5 DAY), DATE_SUB(NOW(), INTERVAL 5 DAY), '库管吴敏'),
    (4,  'TL-2026-0004', 6,  '孙立伟', 'DF-2026-0006 排故',  1, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, '库管郑义'),
    (5,  'TL-2026-0005', 7,  '钱明亮', 'WC-2026-0010 A 检',  4, DATE_SUB(NOW(), INTERVAL 2 DAY), DATE_SUB(NOW(), INTERVAL 2 DAY), '库管郑义'),
    (6,  'TL-2026-0006', 10, '刘振华', 'DF-2026-0011 更换',  6, DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY), '库管吴敏'),
    (7,  'TL-2026-0007', 2,  '陈志强', 'DF-2026-0001 排故',  2, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, '库管郑义'),
    (8,  'TL-2026-0008', 5,  '孙立伟', 'DF-2026-0005 排故',  5, DATE_SUB(NOW(), INTERVAL 1 DAY), NULL, '库管吴敏'),
    (9,  'TL-2026-0009', 12, '何蕊',   'DF-2026-0007 座椅',  8, DATE_SUB(NOW(), INTERVAL 8 DAY), DATE_SUB(NOW(), INTERVAL 8 DAY), '库管吴敏'),
    (10, 'TL-2026-0010', 11, '赵岩',   'WC-2026-0009 复查',  1, DATE_SUB(NOW(), INTERVAL 4 DAY), NULL, '库管郑义'),
    (11, 'TL-2026-0011', 13, '何蕊',   '客舱整新',          10, DATE_SUB(NOW(), INTERVAL 9 DAY), DATE_SUB(NOW(), INTERVAL 9 DAY), '库管吴敏'),
    (12, 'TL-2026-0012', 3,  '周国平', '翼尖修理随工拆装', 1, DATE_SUB(NOW(), INTERVAL 12 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY), '库管郑义');

-- ----------------------------------------------------------------------------
-- 模块六：出厂放行审签
--   release_order          审签单头（active_aircraft_id 唯一占位：同机同时只有一条有效审签）
--   release_snapshot       版本化、不可变的证据快照头
--   release_snapshot_card  快照工卡明细（编号/状态/更新时间永久固化）
--   release_snapshot_defect 快照故障明细（开放/超期 + 保留依据 + 终审逐条确认）
--   release_event          append-only 审计时间线
-- ----------------------------------------------------------------------------
CREATE TABLE release_order (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    release_no         VARCHAR(32) NOT NULL COMMENT '审签单号',
    aircraft_id        BIGINT      NOT NULL COMMENT '飞机 id',
    active_aircraft_id BIGINT      NULL COMMENT '有效审签占位：非终态=aircraft_id，失效/驳回流转结束/出位=NULL',
    status             VARCHAR(16) NOT NULL COMMENT 'DRAFT 草稿 / WAIT_MECH 待机械放行 / WAIT_QA 待质量终审 / RELEASED 已放行 / REJECTED 已驳回 / INVALID 已失效',
    initiator          VARCHAR(32) NOT NULL COMMENT '发起人',
    mech_signer        VARCHAR(32) NULL COMMENT '机械放行人',
    qa_signer          VARCHAR(32) NULL COMMENT '质量终审人',
    submitted_at       DATETIME    NULL COMMENT '最近一次提交时间',
    mech_signed_at     DATETIME    NULL,
    qa_signed_at       DATETIME    NULL COMMENT '终审通过=放行生效时间',
    reject_reason      VARCHAR(255) NULL COMMENT '驳回原因',
    invalid_reason     VARCHAR(255) NULL COMMENT '失效原因',
    invalidated_at     DATETIME    NULL,
    departed_at        DATETIME    NULL COMMENT '出位消费时间',
    departed_bay_no    VARCHAR(16) NULL,
    created_at         DATETIME    NOT NULL,
    updated_at         DATETIME    NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_no (release_no),
    UNIQUE KEY uk_active_aircraft (active_aircraft_id),
    KEY idx_release_aircraft (aircraft_id),
    KEY idx_release_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '出厂放行审签单';

CREATE TABLE release_snapshot (
    id                    BIGINT      NOT NULL AUTO_INCREMENT,
    release_id            BIGINT      NOT NULL,
    version_no            INT         NOT NULL COMMENT '版本号，从 1 起，每次提交 +1',
    snapshot_kind         VARCHAR(16) NOT NULL COMMENT 'DRAFT_SNAPSHOT 发起快照 / SUBMIT_SNAPSHOT 提交快照',
    tail_no               VARCHAR(16) NOT NULL,
    model                 VARCHAR(16) NOT NULL,
    airworthy             VARCHAR(16) NOT NULL COMMENT '当时适航状态',
    bay_id                BIGINT      NULL,
    bay_no                VARCHAR(16) NULL COMMENT '当时所在机位',
    bay_status            VARCHAR(16) NULL,
    open_card_count       INT         NOT NULL DEFAULT 0 COMMENT '当时未闭环工卡数',
    open_defect_count     INT         NOT NULL DEFAULT 0 COMMENT '当时开放故障数',
    overdue_defect_count  INT         NOT NULL DEFAULT 0 COMMENT '当时超期故障数',
    created_at            DATETIME    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_version (release_id, version_no),
    KEY idx_snapshot_release (release_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '放行证据快照（不可变）';

CREATE TABLE release_snapshot_card (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    snapshot_id      BIGINT      NOT NULL,
    card_id          BIGINT      NOT NULL,
    card_no          VARCHAR(32) NOT NULL,
    status_code      VARCHAR(16) NOT NULL COMMENT '当时工卡状态',
    title            VARCHAR(128) NULL,
    executor         VARCHAR(32) NULL,
    inspector        VARCHAR(32) NULL,
    closed_flag      TINYINT     NOT NULL DEFAULT 0 COMMENT '0 未闭环 / 1 已闭环',
    source_updated_at DATETIME   NULL COMMENT '工卡当时的更新时间',
    PRIMARY KEY (id),
    KEY idx_snap_card_snapshot (snapshot_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '证据快照·工卡明细（不可变）';

CREATE TABLE release_snapshot_defect (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    snapshot_id       BIGINT      NOT NULL,
    defect_id         BIGINT      NOT NULL,
    defect_no         VARCHAR(32) NOT NULL,
    status_code       VARCHAR(16) NOT NULL COMMENT '当时故障状态 OPEN / OVERDUE',
    ata_chapter       VARCHAR(16) NULL,
    description       VARCHAR(255) NULL,
    due_date          DATE        NULL,
    overdue_flag      TINYINT     NOT NULL DEFAULT 0 COMMENT '0 开放未超期 / 1 超期',
    retain_basis      VARCHAR(255) NULL COMMENT '开放故障保留依据（逐条）',
    qa_confirmed_flag TINYINT     NOT NULL DEFAULT 0 COMMENT '质量终审是否逐条确认',
    source_updated_at DATETIME    NULL COMMENT '故障当时的更新时间',
    PRIMARY KEY (id),
    KEY idx_snap_defect_snapshot (snapshot_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '证据快照·故障明细（不可变）';

CREATE TABLE release_event (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    release_id       BIGINT      NOT NULL,
    event_type       VARCHAR(16) NOT NULL COMMENT 'CREATE/SUBMIT/MECH_SIGN/QA_CONFIRM/QA_SIGN/REJECT/INVALIDATE/DEPART 等',
    operator         VARCHAR(32) NOT NULL,
    detail           VARCHAR(512) NULL,
    snapshot_version INT         NULL,
    event_at         DATETIME    NOT NULL,
    PRIMARY KEY (id),
    KEY idx_event_release (release_id, event_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '放行审签审计事件（append-only）';

-- ---- 模块六种子：5 条审签单覆盖全部验收路径 ------------------------------

-- [REL-1] RO-2026-0001：B-9001（13），草稿，v1 快照，无开放/超期故障、无未闭环工卡
INSERT INTO release_order (id, release_no, aircraft_id, active_aircraft_id, status, initiator, mech_signer, qa_signer,
                           submitted_at, mech_signed_at, qa_signed_at, reject_reason, invalid_reason, invalidated_at,
                           departed_at, departed_bay_no, created_at, updated_at, version) VALUES
    (1, 'RO-2026-0001', 13, 13, 'DRAFT', '王大鹏', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0),
-- [REL-2] RO-2026-0002：B-9002（14），草稿，v1 快照，1 条开放故障（保留依据待发起人在页面逐条填写）
    (2, 'RO-2026-0002', 14, 14, 'DRAFT', '陈志强', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     DATE_SUB(NOW(), INTERVAL 1 HOUR), DATE_SUB(NOW(), INTERVAL 1 HOUR), 0),
-- [REL-3] RO-2026-0003：B-9003（15），已失效（待机械放行期间新增工卡 WC-2026-0108），机械签字保留备查
    (3, 'RO-2026-0003', 15, NULL, 'INVALID', '孙立伟', '罗建军', NULL,
     DATE_SUB(NOW(), INTERVAL 5 HOUR), DATE_SUB(NOW(), INTERVAL 4 HOUR), NULL, NULL,
     '关联工卡发生变化：新开工卡 WC-2026-0108', DATE_SUB(NOW(), INTERVAL 2 HOUR),
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 6 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR), 0),
-- [REL-4] RO-2026-0004：B-9004（16），待质量终审，已机械放行；两人可同时点终审验收冲突
    (4, 'RO-2026-0004', 16, 16, 'WAIT_QA', '钱明亮', '罗建军', NULL,
     DATE_SUB(NOW(), INTERVAL 3 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR), NULL, NULL, NULL, NULL, NULL, NULL,
     DATE_SUB(NOW(), INTERVAL 4 HOUR), DATE_SUB(NOW(), INTERVAL 2 HOUR), 0),
-- [REL-5] RO-2026-0005：B-9005（17），已放行未出位，机位 BAY-A06 可直接出位
    (5, 'RO-2026-0005', 17, 17, 'RELEASED', '赵岩', '陈志强', '周国平',
     DATE_SUB(NOW(), INTERVAL 8 HOUR), DATE_SUB(NOW(), INTERVAL 7 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR),
     NULL, NULL, NULL, NULL, NULL, DATE_SUB(NOW(), INTERVAL 9 HOUR), DATE_SUB(NOW(), INTERVAL 6 HOUR), 0);

-- 快照头：1/2 号单 v1 发起快照；3 号单 v1 提交快照；4 号单 v1 提交快照；5 号单 v1 提交快照
INSERT INTO release_snapshot (id, release_id, version_no, snapshot_kind, tail_no, model, airworthy,
                              bay_id, bay_no, bay_status, open_card_count, open_defect_count, overdue_defect_count, created_at) VALUES
    (1, 1, 1, 'DRAFT_SNAPSHOT',    'B-9001', 'ARJ21', 'AIRWORTHY', 16, 'BAY-C05', 'OCCUPIED', 0, 0, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (2, 2, 1, 'DRAFT_SNAPSHOT',    'B-9002', 'B737',  'AIRWORTHY', 13, 'BAY-B05', 'OCCUPIED', 0, 1, 0, DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (3, 3, 1, 'SUBMIT_SNAPSHOT',   'B-9003', 'B737',  'AIRWORTHY', 14, 'BAY-B06', 'OCCUPIED', 0, 0, 0, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
    (4, 4, 1, 'SUBMIT_SNAPSHOT',   'B-9004', 'ARJ21', 'AIRWORTHY', 17, 'BAY-C06', 'OCCUPIED', 0, 0, 0, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
    (5, 5, 1, 'SUBMIT_SNAPSHOT',   'B-9005', 'A320',  'AIRWORTHY', 15, 'BAY-A06', 'OCCUPIED', 0, 0, 0, DATE_SUB(NOW(), INTERVAL 8 HOUR));

-- 快照工卡明细：按快照头里的机号（s.release_id 是审单号不是飞机 id，须经 release_order 关联）
-- 从 work_card 当前固化；WC-2026-0108（id=23）刻意排除——它是 3 号单快照【之后】才新增的工卡，
-- 差异视图里以"新增"高亮，正是 [REL-3] 失效演示。
INSERT INTO release_snapshot_card (snapshot_id, card_id, card_no, status_code, title, executor, inspector, closed_flag, source_updated_at)
SELECT s.id, w.id, w.card_no, w.status, w.title, w.executor, w.inspector,
       CASE WHEN w.status IN ('CLOSED', 'CANCELED') THEN 1 ELSE 0 END, w.updated_at
FROM release_snapshot s
JOIN release_order r ON r.id = s.release_id
JOIN work_card w ON w.aircraft_id = r.aircraft_id AND w.id <> 23
ORDER BY s.id, w.card_no;

-- 快照故障明细：2 号快照含 DF-2026-0014（开放、未超期），保留依据与终审确认留空，等页面演示逐条填写
INSERT INTO release_snapshot_defect (snapshot_id, defect_id, defect_no, status_code, ata_chapter, description,
                                     due_date, overdue_flag, retain_basis, qa_confirmed_flag, source_updated_at) VALUES
    (2, 14, 'DF-2026-0014', 'OPEN', '36-12', '1 号客舱空调组件温度调节偏慢（按 MEL 21-2 保留）',
     DATE_ADD(CURDATE(), INTERVAL 8 DAY), 0, NULL, 0, DATE_SUB(NOW(), INTERVAL 2 DAY));

-- 审计事件时间线（append-only 种子）
INSERT INTO release_event (release_id, event_type, operator, detail, snapshot_version, event_at) VALUES
    (1, 'CREATE', '王大鹏', '发起出厂放行审签，已生成 v1 证据快照（草稿）。', 1, DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (2, 'CREATE', '陈志强', '发起出厂放行审签，已生成 v1 证据快照（草稿）。开放故障 1 条，提交前须逐条填写保留依据。', 1, DATE_SUB(NOW(), INTERVAL 1 HOUR)),
    (3, 'CREATE', '孙立伟', '发起出厂放行审签，已生成 v1 证据快照（草稿）。', 1, DATE_SUB(NOW(), INTERVAL 6 HOUR)),
    (3, 'SUBMIT', '孙立伟', '提交进入待机械放行，已生成 v1 证据快照', 1, DATE_SUB(NOW(), INTERVAL 5 HOUR)),
    (3, 'MECH_SIGN', '罗建军', '机械放行通过，提交质量终审', 1, DATE_SUB(NOW(), INTERVAL 4 HOUR)),
    (3, 'INVALIDATE', 'SYSTEM',
        '关联工卡发生变化：新开工卡 WC-2026-0108（已有签字：机械放行 罗建军，质量终审 未签，签字保留作审计但不得沿用）',
        1, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
    (4, 'CREATE', '钱明亮', '发起出厂放行审签，已生成 v1 证据快照（草稿）。', 1, DATE_SUB(NOW(), INTERVAL 4 HOUR)),
    (4, 'SUBMIT', '钱明亮', '提交进入待机械放行，已生成 v1 证据快照', 1, DATE_SUB(NOW(), INTERVAL 3 HOUR)),
    (4, 'MECH_SIGN', '罗建军', '机械放行通过，提交质量终审', 1, DATE_SUB(NOW(), INTERVAL 2 HOUR)),
    (5, 'CREATE', '赵岩', '发起出厂放行审签，已生成 v1 证据快照（草稿）。', 1, DATE_SUB(NOW(), INTERVAL 9 HOUR)),
    (5, 'SUBMIT', '赵岩', '提交进入待机械放行，已生成 v1 证据快照', 1, DATE_SUB(NOW(), INTERVAL 8 HOUR)),
    (5, 'MECH_SIGN', '陈志强', '机械放行通过，提交质量终审', 1, DATE_SUB(NOW(), INTERVAL 7 HOUR)),
    (5, 'QA_SIGN', '周国平', '质量终审通过，出厂放行生效（证据快照 v1），允许出位', 1, DATE_SUB(NOW(), INTERVAL 6 HOUR));

-- ----------------------------------------------------------------------------
-- 模块七：适航指令落实追踪（AD Tracking）
--   ad_directive          指令头（编号唯一），一个编号下多个修订版
--   ad_revision           修订版（不可变：生效后改范围/阈值必须出新版本），
--                         存三类截止阈值 + 三类临期预警提前量 + 适用机型/机号段
--   ad_compliance         落实清单：(修订版, 飞机) 唯一，每个当前结果一行，旧版本行保留
--   ad_compliance_link    落实-工卡关联：(清单, 工卡) 唯一，重复关联不产生两条；
--                         工卡取消/重开/改挂后只置失效标志，关联行不删除（历史不消失）
--   ad_evidence           落实证据：登记文本/文件，可作废但不删除
--   ad_event              append-only 状态变化/证据/重算审计时间线
--   ad_recalc_job(_item)  批量重算作业与逐机明细：范围/进度/成功/失败原因，刷新后一致
-- ----------------------------------------------------------------------------
CREATE TABLE ad_directive (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    ad_no          VARCHAR(32)  NOT NULL COMMENT '适航指令编号，如 CAD-2026-001',
    authority      VARCHAR(64)  NOT NULL COMMENT '发布机构，如 CAAC 华东局',
    title          VARCHAR(255) NOT NULL COMMENT '指令标题',
    latest_revision VARCHAR(16) NOT NULL DEFAULT 'R0' COMMENT '当前最新修订版号',
    created_at     DATETIME     NOT NULL,
    updated_at     DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ad_no (ad_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令（编号头）';

CREATE TABLE ad_revision (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    directive_id     BIGINT       NOT NULL,
    revision_no      VARCHAR(16)  NOT NULL COMMENT '修订版号：R0 原版 / R1 第 1 次修订 …',
    title            VARCHAR(255) NOT NULL COMMENT '该版标题（修订可改题）',
    status           VARCHAR(16)  NOT NULL COMMENT 'DRAFT 草稿 / EFFECTIVE 生效 / SUPERSEDED 已被取代（只读冻结）',
    effective_date   DATE         NOT NULL COMMENT '生效日期',
    applicable_models VARCHAR(128) NOT NULL COMMENT '适用机型，分号分隔，如 B737;A320',
    tail_range_from  VARCHAR(16)  NULL COMMENT '机号范围下界（按机号数字部分比较），NULL=不限',
    tail_range_to    VARCHAR(16)  NULL COMMENT '机号范围上界，NULL=不限；只填一边=边界不完整→待核实',
    -- 三类截止阈值：NULL 表示该维度不限制
    due_date         DATE         NULL COMMENT '日历日期截止阈值',
    due_fh           DECIMAL(12, 2) NULL COMMENT '累计飞行小时截止阈值',
    due_cycles       INT          NULL COMMENT '累计起落次数截止阈值',
    -- 三类临期预警提前量（各自独立配置）
    warn_days        INT          NOT NULL DEFAULT 0 COMMENT '提前多少天进入临期',
    warn_fh          DECIMAL(12, 2) NOT NULL DEFAULT 0 COMMENT '剩余多少飞行小时进入临期',
    warn_cycles      INT          NOT NULL DEFAULT 0 COMMENT '剩余多少起落进入临期',
    revision_note    VARCHAR(255) NULL COMMENT '修订说明/变更摘要',
    created_at       DATETIME     NOT NULL,
    published_at     DATETIME     NULL COMMENT '生效发布时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ad_revision (directive_id, revision_no),
    KEY idx_revision_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令修订版（不可变历史）';

CREATE TABLE ad_compliance (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    revision_id      BIGINT       NOT NULL,
    aircraft_id      BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'NOT_DUE 未到期 / NEAR_DUE 临期 / OVERDUE 超期 / COMPLIED 已落实 / PENDING 待核实 / NOT_APPLICABLE 不适用',
    applicable       TINYINT      NOT NULL DEFAULT 1 COMMENT '该修订版是否适用于该机',
    pending_reasons  VARCHAR(512) NULL COMMENT '待核实原因，分号分隔（累计值缺失/边界不完整/阈值矛盾/工卡失效/证据缺失）',
    limiting_dim     VARCHAR(8)   NULL COMMENT '最早到限维度：DATE / FH / CYCLE',
    days_to_due      INT          NULL COMMENT '距日期截止的天数（负=已过天数）',
    fh_remaining     DECIMAL(12, 2) NULL COMMENT '飞行小时余量（负=已超）',
    cycle_remaining  INT          NULL COMMENT '起落余量（负=已超）',
    calc_tick        BIGINT       NOT NULL DEFAULT 0 COMMENT '本次计算所依据的数据版本 tick（旧 tick 不得覆盖新 tick）',
    calc_at          DATETIME     NULL COMMENT '最近一次计算时间',
    complied_at      DATETIME     NULL COMMENT '最近一次落实生效时间',
    complied_by      VARCHAR(32)  NULL COMMENT '落实登记人',
    created_at       DATETIME     NOT NULL,
    updated_at       DATETIME     NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0 COMMENT 'JPA 乐观锁',
    PRIMARY KEY (id),
    UNIQUE KEY uk_compliance (revision_id, aircraft_id),
    KEY idx_comp_aircraft (aircraft_id),
    KEY idx_comp_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令落实清单（按修订版+飞机）';

CREATE TABLE ad_compliance_link (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    compliance_id    BIGINT       NOT NULL,
    work_card_id     BIGINT       NOT NULL,
    card_no          VARCHAR(32)  NOT NULL COMMENT '关联时工卡号（固化，工卡后续变化不影响此列）',
    linked_aircraft_id BIGINT     NOT NULL COMMENT '关联时工卡所属飞机（用于发现改挂）',
    linked_by        VARCHAR(32)  NOT NULL,
    linked_at        DATETIME     NOT NULL,
    valid_flag       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 当前有效 / 0 已失效（取消/重开/改挂），行保留不删',
    invalid_reason   VARCHAR(255) NULL,
    invalidated_at   DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_comp_card (compliance_id, work_card_id),
    KEY idx_link_card (work_card_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '落实-工卡关联（历史不删除）';

CREATE TABLE ad_evidence (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    compliance_id    BIGINT       NOT NULL,
    evidence_type    VARCHAR(16)  NOT NULL COMMENT 'REGISTER 登记文本 / FILE 上传文件',
    title            VARCHAR(128) NOT NULL COMMENT '证据标题',
    evidence_ref     VARCHAR(255) NULL COMMENT '文本登记号或文件存储路径',
    file_name        VARCHAR(255) NULL COMMENT '原始文件名',
    registered_by    VARCHAR(32)  NOT NULL,
    registered_at    DATETIME     NOT NULL,
    void_flag        TINYINT      NOT NULL DEFAULT 0 COMMENT '0 有效 / 1 已作废（行保留）',
    void_reason      VARCHAR(255) NULL,
    voided_at        DATETIME     NULL,
    PRIMARY KEY (id),
    KEY idx_evidence_comp (compliance_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令落实证据（可作废不删除）';

CREATE TABLE ad_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    directive_id     BIGINT       NOT NULL,
    revision_id      BIGINT       NULL,
    compliance_id    BIGINT       NULL,
    aircraft_id      BIGINT       NULL,
    event_type       VARCHAR(24)  NOT NULL COMMENT 'REVISION_PUBLISH/RECALC/STATUS_CHANGE/COMPLY/LINK/EVIDENCE/EVIDENCE_VOID/RECALC_JOB 等',
    operator         VARCHAR(32)  NOT NULL,
    detail           VARCHAR(512) NOT NULL,
    from_status      VARCHAR(16)  NULL,
    to_status        VARCHAR(16)  NULL,
    event_at         DATETIME     NOT NULL,
    PRIMARY KEY (id),
    KEY idx_ad_event_directive (directive_id, event_at),
    KEY idx_ad_event_comp (compliance_id, event_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令审计事件（append-only）';

CREATE TABLE ad_recalc_job (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type       VARCHAR(16)  NOT NULL COMMENT 'REVISION 按指令修订版 / FLEET 全机队 / AIRCRAFT 单架飞机',
    directive_id     BIGINT       NULL,
    revision_id      BIGINT       NULL,
    aircraft_id      BIGINT       NULL,
    trigger_reason   VARCHAR(255) NOT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'RUNNING 运行中 / DONE 全部成功 / PARTIAL_FAILED 部分失败 / DONE_WITH_SKIP 完成（有不适用）',
    total_count      INT          NOT NULL DEFAULT 0 COMMENT '本次重算范围（架/项）',
    success_count    INT          NOT NULL DEFAULT 0,
    failed_count     INT          NOT NULL DEFAULT 0,
    na_count         INT          NOT NULL DEFAULT 0 COMMENT '不适用项数（也算处理成功）',
    fail_inject      VARCHAR(64)  NULL COMMENT '验收用：注入失败的飞机 id 列表（逗号分隔），仅在项目未处理过时失败一次',
    started_at       DATETIME     NOT NULL,
    finished_at      DATETIME     NULL,
    created_by       VARCHAR(32)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_recalc_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '适航指令批量重算作业';

CREATE TABLE ad_recalc_job_item (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    job_id           BIGINT       NOT NULL,
    revision_id      BIGINT       NOT NULL,
    aircraft_id      BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL COMMENT 'PENDING / SUCCESS / FAILED / NOT_APPLICABLE',
    error_message    VARCHAR(512) NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    updated_at       DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recalc_item (job_id, revision_id, aircraft_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '批量重算作业逐机明细';

-- ----------------------- 模块七种子：指令与修订版 --------------------------------
-- 日期全部用 CURDATE() 表达式，保证任意一天初始化都能命中验收状态
INSERT INTO ad_directive (id, ad_no, authority, title, latest_revision, created_at, updated_at) VALUES
    (1,  'CAD-2026-001', 'CAAC 华东局', 'B737 机翼前梁连接螺栓检查', 'R1', DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (2,  'CAD-2025-014', 'CAAC 适航司', 'B737 发动机吊架耐磨衬套时限更换', 'R1', DATE_SUB(NOW(), INTERVAL 90 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY)),
    (3,  'CAD-2026-007', 'CAAC 华北局', 'B737 前起落架舱门限位钢索检查', 'R1', DATE_SUB(NOW(), INTERVAL 40 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (4,  'CAD-2026-004', 'EASA',        'A320 升降舵后梁铰链点润滑检查', 'R1', DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 60 DAY)),
    (5,  'CAD-2026-005', 'CAAC 新疆局', 'ARJ21 高原构型氧气压力发生器寿命核查', 'R1', DATE_SUB(NOW(), INTERVAL 70 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (6,  'CAD-2026-006', 'CAAC 华东局', 'ARJ21 风挡加温控制器软件升级', 'R0', DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (7,  'CAD-2026-008', 'CAAC 适航司', 'A320 燃油箱点火源防护导线复查（阈值矛盾验收）', 'R0', DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (8,  'CAD-2026-009', 'CAAC 华北局', 'A330 客舱应急灯电池容量核查（机号边界不完整验收）', 'R0', DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY)),
    (9,  'CAD-2026-011', 'FAA',         'B737/A320 飞行控制计算机批次排查', 'R1', DATE_SUB(NOW(), INTERVAL 80 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY)),
    (10, 'CAD-2026-010', 'CAAC 华东局', '货舱烟雾探测器改装（工卡改挂验收）', 'R1', DATE_SUB(NOW(), INTERVAL 50 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY)),
    (11, 'CAD-2026-020', 'CAAC 华南局', 'A330 发动机防冰活门管路检查（工卡取消验收）', 'R1', DATE_SUB(NOW(), INTERVAL 50 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY));

-- [AD-01] R1：仅日期阈值，截止 = 今天+20 天，提前 30 天预警 → 全系 6 架 B737 均临期
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (1, 1, 'R1', 'B737 机翼前梁连接螺栓检查（修订1：扩大全部 B737）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'B737', NULL, NULL,
     DATE_ADD(CURDATE(), INTERVAL 20 DAY), NULL, NULL,
     30, 0, 0, 'R1 将适用范围从部分机号扩大到全部 B737',
     DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- [AD-02] R1：FH 25000 / CYC 30000，无日期。B737 各机 FH 余量普遍小于 CYC 余量 → 飞行小时先到期
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (2, 2, 'R1', 'B737 发动机吊架耐磨衬套时限更换（按累计 FH/CYC 先到为准）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 30 DAY), 'B737', NULL, NULL,
     NULL, 25000.00, 30000,
     0, 500.00, 2000, 'R1 收紧飞行小时阈值',
     DATE_SUB(NOW(), INTERVAL 30 DAY), DATE_SUB(NOW(), INTERVAL 30 DAY));

-- [AD-03] R1：FH 33000 / CYC 18500。各 B737 的起落余量更小 → 起落次数先到期
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (3, 3, 'R1', 'B737 前起落架舱门限位钢索检查（按累计 FH/CYC 先到为准）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 10 DAY), 'B737', NULL, NULL,
     NULL, 33000.00, 18500,
     0, 3000.00, 800, NULL,
     DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- [AD-04] R1：A320 全系，日期截止已过 5 天（超期基准）；7 号机已凭 WC-2026-0201 落实
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (4, 4, 'R1', 'A320 升降舵后梁铰链点润滑检查', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 25 DAY), 'A320', NULL, NULL,
     DATE_SUB(CURDATE(), INTERVAL 5 DAY), NULL, NULL,
     10, 0, 0, NULL,
     DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 60 DAY));

-- [AD-05] R1（当前）：ARJ21 且机号段 11001~11099；旧版 R0 仅 11020~11099（历史冻结行）
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (5, 5, 'R0', 'ARJ21 氧气压力发生器核查（原版：仅 11020~11099）', 'SUPERSEDED',
     DATE_SUB(CURDATE(), INTERVAL 40 DAY), 'ARJ21', 'B-11020', 'B-11099',
     DATE_ADD(CURDATE(), INTERVAL 60 DAY), NULL, NULL,
     20, 0, 0, '原版，已被 R1 取代',
     DATE_SUB(NOW(), INTERVAL 60 DAY), DATE_SUB(NOW(), INTERVAL 60 DAY)),
    (6, 5, 'R1', 'ARJ21 高原构型氧气压力发生器寿命核查（扩段 11001~11099）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ARJ21', 'B-11001', 'B-11099',
     DATE_ADD(CURDATE(), INTERVAL 60 DAY), 10000.00, 11000,
     20, 600.00, 900, 'R1 扩大机号段并增补 FH/CYC 阈值',
     DATE_SUB(NOW(), INTERVAL 10 DAY), DATE_SUB(NOW(), INTERVAL 10 DAY));

-- [AD-06] R0：ARJ21 全系，机号范围只填上界（边界不完整）→ 适用机全部待核实
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (7, 6, 'R0', 'ARJ21 风挡加温控制器软件升级（机号下界待补录）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'ARJ21', NULL, 'B-11999',
     DATE_ADD(CURDATE(), INTERVAL 40 DAY), 12000.00, 13000,
     15, 500.00, 800, '机号范围下界尚未明确，待局方补充',
     DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- [AD-07] R0：A320 全系，截止日期早于生效日期（阈值矛盾）→ 待核实
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (8, 7, 'R0', 'A320 燃油箱点火源防护导线复查（截止日期填报待局方更正）', 'EFFECTIVE',
     DATE_ADD(CURDATE(), INTERVAL 10 DAY), 'A320', NULL, NULL,
     DATE_SUB(CURDATE(), INTERVAL 8 DAY), 24000.00, NULL,
     10, 800.00, 0, '初版日期阈值与生效日期矛盾，待发布勘误',
     DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- [AD-08] R0：A330 全系，机号范围只填下界（边界不完整）→ 待核实
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (9, 8, 'R0', 'A330 客舱应急灯电池容量核查（机号上界待补录）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'A330', 'B-6000', NULL,
     DATE_ADD(CURDATE(), INTERVAL 30 DAY), NULL, 12000,
     10, 0, 1000, '机号范围上界尚未明确',
     DATE_SUB(NOW(), INTERVAL 20 DAY), DATE_SUB(NOW(), INTERVAL 20 DAY));

-- [AD-09] CAD-2026-011：R0 旧版只适用 B737（已取代冻结）；R1 扩到 B737;A320
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (10, 9, 'R0', '飞控计算机批次排查（原版仅 B737）', 'SUPERSEDED',
     DATE_SUB(CURDATE(), INTERVAL 50 DAY), 'B737', NULL, NULL,
     DATE_ADD(CURDATE(), INTERVAL 90 DAY), NULL, NULL,
     20, 0, 0, '原版，已被 R1 取代',
     DATE_SUB(NOW(), INTERVAL 70 DAY), DATE_SUB(NOW(), INTERVAL 70 DAY)),
    (11, 9, 'R1', '飞控计算机批次排查（修订1：扩展 A320 同批次件号）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 5 DAY), 'B737;A320', NULL, NULL,
     DATE_ADD(CURDATE(), INTERVAL 18 DAY), NULL, NULL,
     25, 0, 0, 'R1 扩展 A320 适用范围',
     DATE_SUB(NOW(), INTERVAL 15 DAY), DATE_SUB(NOW(), INTERVAL 15 DAY));

-- [AD-10] R1：B737;A330，CYC 阈值 32000（各机都还远），日期 400 天；3 号机曾凭 0203 落实，0203 已改挂 8 号机
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (12, 10, 'R1', '货舱烟雾探测器改装（修订1：增补 A330）', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 3 DAY), 'B737;A330', NULL, NULL,
     DATE_ADD(CURDATE(), INTERVAL 400 DAY), 60000.00, 32000,
     30, 1000.00, 1500, 'R1 增补 A330 适用性',
     DATE_SUB(NOW(), INTERVAL 6 DAY), DATE_SUB(NOW(), INTERVAL 6 DAY));

-- [AD-11] R1：A330，CYC 12000 → 3 号/8 号都超期；3 号机曾关联已取消工卡 0204
INSERT INTO ad_revision (id, directive_id, revision_no, title, status, effective_date, applicable_models,
                         tail_range_from, tail_range_to, due_date, due_fh, due_cycles,
                         warn_days, warn_fh, warn_cycles, revision_note, created_at, published_at) VALUES
    (13, 11, 'R1', 'A330 发动机防冰活门管路检查', 'EFFECTIVE',
     DATE_SUB(CURDATE(), INTERVAL 2 DAY), 'A330', NULL, NULL,
     DATE_ADD(CURDATE(), INTERVAL 200 DAY), NULL, 12000,
     10, 0, 1000, NULL,
     DATE_SUB(NOW(), INTERVAL 3 DAY), DATE_SUB(NOW(), INTERVAL 3 DAY));

-- ----------------------- 模块七种子：历史版本/失效关联的落实行 -----------------------
-- 这些行表达"修订前已落实、修订后历史冻结"与"工卡/证据失效后回待核实"，
-- 当前生效版本的其余清单行由后端启动引导（AdBootstrap）按最新数据幂等补齐，不手工写死状态。

-- R0（id=5）冻结行：20 号机曾 COMPLIED（工卡 0202 后被重开）；19 号机当时不适用（下界 11020）
INSERT INTO ad_compliance (id, revision_id, aircraft_id, status, applicable, pending_reasons, limiting_dim,
                           days_to_due, fh_remaining, cycle_remaining, calc_tick, calc_at, complied_at,
                           complied_by, created_at, updated_at, version) VALUES
    (1, 5, 20, 'COMPLIED', 1, NULL, 'DATE', 60, 2200.00, 2550, 0, DATE_SUB(NOW(), INTERVAL 25 DAY),
     DATE_SUB(NOW(), INTERVAL 25 DAY), '罗建军', DATE_SUB(NOW(), INTERVAL 25 DAY), DATE_SUB(NOW(), INTERVAL 25 DAY), 0),
    (2, 5, 19, 'NOT_APPLICABLE', 0, NULL, NULL, NULL, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 25 DAY),
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 25 DAY), DATE_SUB(NOW(), INTERVAL 25 DAY), 0);

INSERT INTO ad_compliance_link (id, compliance_id, work_card_id, card_no, linked_aircraft_id, linked_by,
                                linked_at, valid_flag, invalid_reason, invalidated_at) VALUES
    (1, 1, 26, 'WC-2026-0202', 20, '罗建军', DATE_SUB(NOW(), INTERVAL 25 DAY), 0,
     '该工卡关闭后被重新打开（当前状态：待检验），历史落实凭证失效，R0 已冻结留存', DATE_SUB(NOW(), INTERVAL 2 HOUR));

INSERT INTO ad_evidence (id, compliance_id, evidence_type, title, evidence_ref, file_name, registered_by,
                         registered_at, void_flag, void_reason, voided_at) VALUES
    (1, 1, 'REGISTER', 'R0 历史落实：工作单签署页编号', 'ARC-R0-20260904-020', NULL, '罗建军',
     DATE_SUB(NOW(), INTERVAL 25 DAY), 0, NULL, NULL);

INSERT INTO ad_event (directive_id, revision_id, compliance_id, aircraft_id, event_type, operator, detail,
                      from_status, to_status, event_at) VALUES
    (5, 5, 1, 20, 'COMPLY', '罗建军', 'R0 版本落实：关联工卡 WC-2026-0202 并登记签署页 ARC-R0-20260904-020',
     NULL, 'COMPLIED', DATE_SUB(NOW(), INTERVAL 25 DAY)),
    (5, NULL, NULL, NULL, 'REVISION_PUBLISH', 'SYSTEM', 'R1 生效：机号段扩大为 B-11001~B-11099，R0 及其判断结果冻结保留',
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 10 DAY)),
    (5, 5, 1, 20, 'STATUS_CHANGE', 'SYSTEM', '关联工卡 WC-2026-0202 被重新打开，R0 历史凭证标记失效（R0 已冻结，状态仅作历史展示）',
     'COMPLIED', 'PENDING', DATE_SUB(NOW(), INTERVAL 2 HOUR));

-- R0（id=10）冻结行：CAD-2026-011 旧版仅 B737，1 号机当时临期（历史留存）
INSERT INTO ad_compliance (id, revision_id, aircraft_id, status, applicable, pending_reasons, limiting_dim,
                           days_to_due, fh_remaining, cycle_remaining, calc_tick, calc_at, complied_at,
                           complied_by, created_at, updated_at, version) VALUES
    (3, 10, 1, 'NEAR_DUE', 1, NULL, 'DATE', 18, NULL, NULL, 0, DATE_SUB(NOW(), INTERVAL 16 DAY),
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 16 DAY), DATE_SUB(NOW(), INTERVAL 16 DAY), 0);

INSERT INTO ad_event (directive_id, revision_id, compliance_id, aircraft_id, event_type, operator, detail,
                      from_status, to_status, event_at) VALUES
    (9, 10, 3, 1, 'STATUS_CHANGE', 'SYSTEM', 'R0 历史计算：1 号机临期（R0 后被 R1 取代，结果冻结）',
     'NOT_DUE', 'NEAR_DUE', DATE_SUB(NOW(), INTERVAL 16 DAY)),
    (9, NULL, NULL, NULL, 'REVISION_PUBLISH', 'SYSTEM', 'R1 生效：适用范围扩展为 B737;A320，R0 判断结果与证据保留',
     NULL, NULL, DATE_SUB(NOW(), INTERVAL 15 DAY));

SET FOREIGN_KEY_CHECKS = 1;
