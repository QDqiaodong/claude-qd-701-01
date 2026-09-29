package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;

import com.aircraft.mro.ad.service.AdComplianceCoordinator;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.dto.WorkCardDtos;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 模块三：定检工卡。
 *
 * <p>状态机：待派工 ASSIGNED → 进行中 IN_PROGRESS → 待检验 WAIT_INSP → 已关闭 CLOSED，
 * 待派工/进行中还可以取消 CANCELED；已关闭与已取消是终态，不可再流转。
 * 每一步的合法性由 {@link MroDict#cardFlowAllowed} 判，service 只负责取数与提示。
 */
@Service
public class WorkCardService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 时间轴节点配色（前端直接用） */
    private static final Map<String, String> STEP_COLOR = Map.of(
            "ASSIGN", "#90a4ae",
            "START", "#1565c0",
            "FINISH", "#0288d1",
            "INSP", "#f9a825",
            "CLOSE", "#2e7d32",
            "CANCELED", "#c62828");

    private final WorkCardRepository workCardRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;
    private final AdComplianceCoordinator adComplianceCoordinator;

    public WorkCardService(WorkCardRepository workCardRepository, AircraftRepository aircraftRepository,
                           ReleaseService releaseService,
                           @Lazy AdComplianceCoordinator adComplianceCoordinator) {
        this.workCardRepository = workCardRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
        this.adComplianceCoordinator = adComplianceCoordinator;
    }

    @Transactional(readOnly = true)
    public List<Views.WorkCardView> list(Long aircraftId) {
        List<WorkCard> cards = aircraftId == null
                ? workCardRepository.findAllByOrderByCardNoAsc()
                : workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId);
        Map<Long, String> tailMap = tailMap();
        List<Views.WorkCardView> list = new ArrayList<>();
        for (WorkCard card : cards) {
            list.add(toView(card, tailMap));
        }
        return list;
    }

    /**
     * 工卡执行流水：派工 → 开工 → 完工 → 检验 → 关闭，节点带状态色点。
     * 时间取自工卡自身的开工/完工/更新时间，未走到的步骤不生成节点。
     */
    @Transactional(readOnly = true)
    public List<Views.TimelineNode> timeline(Long aircraftId) {
        List<WorkCard> cards = aircraftId == null
                ? workCardRepository.findAllByOrderByCardNoAsc()
                : workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId);
        Map<Long, String> tailMap = tailMap();
        List<Views.TimelineNode> nodes = new ArrayList<>();
        for (WorkCard card : cards) {
            String tail = tailMap.getOrDefault(card.getAircraftId(), "未知机号");
            String title = tail + " · " + card.getCardNo() + " " + card.getTitle();
            if ("CANCELED".equals(card.getStatus())) {
                nodes.add(node(card, "CANCELED", title, "已取消", card.getUpdatedAt(), true));
                continue;
            }
            LocalDateTime assignTime = card.getStartTime() != null ? card.getStartTime() : card.getUpdatedAt();
            nodes.add(node(card, "ASSIGN", title, "派工"
                    + (card.getExecutor() == null ? "" : "：" + card.getExecutor()), assignTime, true));
            boolean started = card.getStartTime() != null;
            if (started) {
                nodes.add(node(card, "START", title, "开工", card.getStartTime(), true));
            }
            boolean finished = card.getEndTime() != null;
            if (finished) {
                nodes.add(node(card, "FINISH", title, "完工（实测 " + card.getActualHour() + " 工时）",
                        card.getEndTime(), true));
            }
            if ("CLOSED".equals(card.getStatus())) {
                nodes.add(node(card, "INSP", title, "检验"
                        + (card.getInspector() == null ? "" : "：" + card.getInspector()), card.getEndTime(), true));
                nodes.add(node(card, "CLOSE", title, "关闭工卡", card.getUpdatedAt(), true));
            } else if ("WAIT_INSP".equals(card.getStatus())) {
                // 待检验：下一个待办节点，标灰
                nodes.add(node(card, "INSP", title, "待检验", null, false));
            } else if ("IN_PROGRESS".equals(card.getStatus())) {
                nodes.add(node(card, "FINISH", title, "待完工", null, false));
            } else {
                nodes.add(node(card, "START", title, "待开工", null, false));
            }
        }
        nodes.sort(Comparator.comparing((Views.TimelineNode n) -> n.time() == null ? "9999-99-99 99:99" : n.time())
                .thenComparing(Views.TimelineNode::cardNo)
                .thenComparing(Views.TimelineNode::step));
        return nodes;
    }

    @Transactional
    public Views.WorkCardView create(WorkCardDtos.CardSaveReq req) {
        String cardNo = trim(req.getCardNo());
        if (workCardRepository.existsByCardNo(cardNo)) {
            throw new BizException("工卡号 " + cardNo + " 已存在，不能重复开卡");
        }
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择工卡所属的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        if (MroDict.AIRWORTHY_STORED.equals(aircraft.getAirworthy())) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 处于封存状态，不能开定检工卡");
        }
        String checkType = trim(req.getCheckType()).toUpperCase();
        if (!MroDict.isValidCheckType(checkType)) {
            throw new BizException("检类只能是 A_CHECK / C_CHECK / ROUTINE / SPECIAL，收到的是：" + req.getCheckType());
        }
        BigDecimal plannedHour = req.getPlannedHour() == null ? BigDecimal.ZERO : req.getPlannedHour();
        if (plannedHour.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("计划工时必须大于 0");
        }
        if (plannedHour.compareTo(MroDict.checkMaxPlanHour(checkType)) > 0) {
            throw new BizException(MroDict.checkTypeName(checkType) + "的计划工时上限是 "
                    + MroDict.checkMaxPlanHour(checkType) + " 小时，收到的是 " + plannedHour);
        }
        WorkCard card = new WorkCard();
        card.setCardNo(cardNo);
        card.setAircraftId(aircraft.getId());
        card.setCheckType(checkType);
        card.setTitle(trim(req.getTitle()));
        card.setPlannedHour(plannedHour);
        card.setActualHour(BigDecimal.ZERO);
        card.setStartTime(null);
        card.setEndTime(null);
        card.setStatus("ASSIGNED");
        card.setExecutor(req.getExecutor() == null || req.getExecutor().isBlank() ? null : req.getExecutor().trim());
        card.setInspector(null);
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        // 工卡事实变化：该机待审放行立即失效（新开工卡 = 新增关联工卡）
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡发生变化：新开工卡 " + card.getCardNo());
        return toView(card, tailMap());
    }

    /** 待派工 → 进行中 */
    @Transactional
    public Views.WorkCardView start(Long id, WorkCardDtos.StartReq req) {
        WorkCard card = requireCard(id);
        requireFlow(card, "IN_PROGRESS");
        String executor = req.getExecutor().trim();
        if (executor.isEmpty()) {
            throw new BizException("开工前必须指定执行人");
        }
        card.setStatus("IN_PROGRESS");
        card.setExecutor(executor);
        card.setStartTime(LocalDateTime.now());
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡 " + card.getCardNo() + " 状态变为「进行中」");
        return toView(card, tailMap());
    }

    /** 进行中 → 待检验（完工） */
    @Transactional
    public Views.WorkCardView finish(Long id, WorkCardDtos.FinishReq req) {
        WorkCard card = requireCard(id);
        requireFlow(card, "WAIT_INSP");
        BigDecimal actualHour = req == null || req.getActualHour() == null
                ? hoursBetween(card.getStartTime(), LocalDateTime.now())
                : req.getActualHour();
        if (actualHour.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("实测工时必须大于 0 才能报完工");
        }
        if (actualHour.compareTo(MroDict.MAX_ACTUAL_HOUR) > 0) {
            throw new BizException("单张工卡实测工时不能超过 " + MroDict.MAX_ACTUAL_HOUR + " 小时");
        }
        card.setStatus("WAIT_INSP");
        card.setActualHour(actualHour.setScale(2, RoundingMode.HALF_UP));
        card.setEndTime(LocalDateTime.now());
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡 " + card.getCardNo() + " 状态变为「待检验」");
        return toView(card, tailMap());
    }

    /** 待检验 → 已关闭（检验通过） */
    @Transactional
    public Views.WorkCardView inspect(Long id, WorkCardDtos.InspectReq req) {
        WorkCard card = requireCard(id);
        requireFlow(card, "CLOSED");
        String inspector = req.getInspector().trim();
        if (inspector.isEmpty()) {
            throw new BizException("关闭工卡前必须填写检验员");
        }
        if (inspector.equals(card.getExecutor())) {
            throw new BizException("执行人与检验员不能是同一人，检验员收到的是：" + inspector);
        }
        card.setStatus("CLOSED");
        card.setInspector(inspector);
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡 " + card.getCardNo() + " 已检验关闭");
        // 新关闭的工卡可能成为待核实落实记录的有效依据：按最新事实复核一次
        adComplianceCoordinator.onWorkCardChanged(card.getId(),
                "工卡 " + card.getCardNo() + " 已检验关闭");
        return toView(card, tailMap());
    }

    /** 取消工卡：只有待派工 / 进行中可取消 */
    @Transactional
    public Views.WorkCardView cancel(Long id) {
        WorkCard card = requireCard(id);
        requireFlow(card, "CANCELED");
        card.setStatus("CANCELED");
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡 " + card.getCardNo() + " 被取消");
        // 适航指令：以该卡为已关闭依据的落实结果立即回待核实（历史关联保留）
        adComplianceCoordinator.onWorkCardChanged(card.getId(),
                "关联工卡 " + card.getCardNo() + " 被取消");
        return toView(card, tailMap());
    }

    /**
     * 已关闭 → 重新打开（回到进行中）：复检发现需要补充作业时使用。
     * 已取消是终态不能重开。以该卡为落实依据的适航指令结果同步回待核实。
     */
    @Transactional
    public Views.WorkCardView reopen(Long id, WorkCardDtos.ReopenReq req) {
        WorkCard card = requireCard(id);
        requireFlow(card, "IN_PROGRESS");
        String reason = req == null || req.getReason() == null || req.getReason().isBlank()
                ? "复检发现需补充作业" : req.getReason().trim();
        card.setStatus("IN_PROGRESS");
        card.setInspector(null);
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(card.getAircraftId(),
                "关联工卡 " + card.getCardNo() + " 已关闭后被重新打开");
        adComplianceCoordinator.onWorkCardChanged(card.getId(),
                "关联工卡 " + card.getCardNo() + " 已关闭后重新打开为进行中（原因：" + reason + "）");
        return toView(card, tailMap());
    }

    /**
     * 改挂工卡到另一架飞机（挂错机的纠正操作）。已取消/已关闭工卡不允许改挂；
     * 原飞机若以它为落实依据，结果回待核实；新飞机的指令清单按最新事实重算。
     */
    @Transactional
    public Views.WorkCardView reassign(Long id, WorkCardDtos.ReassignReq req) {
        WorkCard card = requireCard(id);
        if (req == null || req.getAircraftId() == null) {
            throw new BizException("请选择要改挂到的飞机");
        }
        Long oldAircraftId = card.getAircraftId();
        if (oldAircraftId.equals(req.getAircraftId())) {
            throw new BizException("工卡 " + card.getCardNo() + " 本来就挂在这架飞机上，无需改挂");
        }
        if ("CLOSED".equals(card.getStatus()) || "CANCELED".equals(card.getStatus())) {
            throw new BizException("工卡 " + card.getCardNo() + " 已" + MroDict.cardStatusName(card.getStatus())
                    + "，不能再改挂飞机");
        }
        Aircraft target = aircraftRepository.findById(req.getAircraftId())
                .orElseThrow(() -> new BizException("飞机不存在：id=" + req.getAircraftId()));
        if (MroDict.AIRWORTHY_STORED.equals(target.getAirworthy())) {
            throw new BizException("飞机 " + target.getTailNo() + " 处于封存状态，不能把工卡改挂过去");
        }
        card.setAircraftId(target.getId());
        card.setUpdatedAt(LocalDateTime.now());
        card = workCardRepository.save(card);
        releaseService.invalidateOnFactChange(oldAircraftId,
                "关联工卡 " + card.getCardNo() + " 已改挂到 " + target.getTailNo());
        releaseService.invalidateOnFactChange(target.getId(),
                "工卡 " + card.getCardNo() + " 由其他飞机改挂入本机");
        // 适航指令：旧机的落实依据失效，新机按最新事实重算
        adComplianceCoordinator.onWorkCardChanged(card.getId(),
                "工卡 " + card.getCardNo() + " 已改挂到 " + target.getTailNo());
        return toView(card, tailMap());
    }

    private WorkCard requireCard(Long id) {
        return workCardRepository.findById(id).orElseThrow(() -> new BizException("工卡不存在：id=" + id));
    }

    private void requireFlow(WorkCard card, String target) {
        if (!MroDict.cardFlowAllowed(card.getStatus(), target)) {
            throw new BizException("工卡 " + card.getCardNo() + " 当前状态为"
                    + MroDict.cardStatusName(card.getStatus()) + "，不能流转到"
                    + MroDict.cardStatusName(target));
        }
    }

    private BigDecimal hoursBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null) {
            return BigDecimal.ZERO;
        }
        long minutes = Duration.between(from, to).toMinutes();
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private Views.TimelineNode node(WorkCard card, String step, String title, String label,
                                    LocalDateTime time, boolean done) {
        return new Views.TimelineNode(card.getId(), card.getCardNo(), step, title + " —— " + label,
                time == null ? null : FMT.format(time), STEP_COLOR.getOrDefault(step, "#90a4ae"), done);
    }

    private Map<Long, String> tailMap() {
        Map<Long, String> map = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(aircraft.getId(), aircraft.getTailNo());
        }
        return map;
    }

    private Views.WorkCardView toView(WorkCard card, Map<Long, String> tailMap) {
        return new Views.WorkCardView(card.getId(), card.getCardNo(), card.getAircraftId(),
                tailMap.get(card.getAircraftId()), card.getCheckType(), MroDict.checkTypeName(card.getCheckType()),
                card.getTitle(), card.getPlannedHour(), card.getActualHour(), card.getStartTime(), card.getEndTime(),
                card.getStatus(), MroDict.cardStatusName(card.getStatus()), card.getExecutor(), card.getInspector(),
                card.getUpdatedAt());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
