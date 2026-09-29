package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.annotation.Lazy;

import com.aircraft.mro.ad.service.AdComplianceCoordinator;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCardSummary;
import com.aircraft.mro.dto.AircraftDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;
import com.aircraft.mro.repository.WorkCardSummaryRepository;

/**
 * 模块二：飞机台账。
 *
 * <p>列表页的工卡汇总列（工卡总数 / 已关闭 / 未闭环 / 累计工时）全部来自
 * {@link WorkCardSummary} —— 那个 {@code @Subselect} 只读视图实体，
 * 这里一次性捞出全量汇总再按飞机 id 建索引，避免 N+1。
 */
@Service
public class AircraftService {

    private final AircraftRepository aircraftRepository;
    private final WorkCardSummaryRepository summaryRepository;
    private final BayRepository bayRepository;
    private final ReleaseService releaseService;
    private final AdComplianceCoordinator adComplianceCoordinator;

    public AircraftService(AircraftRepository aircraftRepository,
                           WorkCardSummaryRepository summaryRepository,
                           BayRepository bayRepository,
                           ReleaseService releaseService,
                           @Lazy AdComplianceCoordinator adComplianceCoordinator) {
        this.aircraftRepository = aircraftRepository;
        this.summaryRepository = summaryRepository;
        this.bayRepository = bayRepository;
        this.releaseService = releaseService;
        this.adComplianceCoordinator = adComplianceCoordinator;
    }

    @Transactional(readOnly = true)
    public List<Views.AircraftView> list() {
        // 一次性取全量汇总视图，避免逐机查询
        Map<Long, WorkCardSummary> summaryMap = new HashMap<>();
        for (WorkCardSummary summary : summaryRepository.findAll()) {
            summaryMap.put(summary.getId(), summary);
        }
        Map<Long, String> bayMap = new HashMap<>();
        for (Bay bay : bayRepository.findAllByOrderByBayNoAsc()) {
            if (bay.getCurrentAircraftId() != null) {
                bayMap.put(bay.getCurrentAircraftId(), bay.getBayNo());
            }
        }
        List<Views.AircraftView> list = new ArrayList<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            WorkCardSummary summary = summaryMap.get(aircraft.getId());
            list.add(new Views.AircraftView(
                    aircraft.getId(),
                    aircraft.getTailNo(),
                    aircraft.getModel(),
                    aircraft.getAirline(),
                    aircraft.getTotalFh(),
                    aircraft.getTotalCycles(),
                    aircraft.getAirworthy(),
                    MroDict.airworthyName(aircraft.getAirworthy()),
                    aircraft.getNote(),
                    summary == null ? 0L : nullSafe(summary.getTotalCard()),
                    summary == null ? 0L : nullSafe(summary.getClosedCard()),
                    summary == null ? 0L : nullSafe(summary.getOpenCard()),
                    summary == null || summary.getTotalHour() == null ? BigDecimal.ZERO : summary.getTotalHour(),
                    bayMap.get(aircraft.getId()),
                    aircraft.getUpdatedAt()));
        }
        return list;
    }

    /**
     * 【@Subselect 汇总接口】按飞机聚合工卡数与工时，按累计工时倒序。
     */
    @Transactional(readOnly = true)
    public List<Views.CardSummaryView> cardSummary() {
        Map<Long, Aircraft> aircraftMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            aircraftMap.put(aircraft.getId(), aircraft);
        }
        List<Views.CardSummaryView> list = new ArrayList<>();
        for (WorkCardSummary summary : summaryRepository.findAllByOrderByTotalHourDesc()) {
            Aircraft aircraft = aircraftMap.get(summary.getId());
            list.add(new Views.CardSummaryView(
                    summary.getId(),
                    aircraft == null ? null : aircraft.getTailNo(),
                    aircraft == null ? null : aircraft.getModel(),
                    aircraft == null ? null : aircraft.getAirline(),
                    nullSafe(summary.getTotalCard()),
                    nullSafe(summary.getClosedCard()),
                    nullSafe(summary.getOpenCard()),
                    summary.getTotalHour() == null ? BigDecimal.ZERO : summary.getTotalHour()));
        }
        return list;
    }

    @Transactional
    public Views.AircraftView create(AircraftDtos.AircraftSaveReq req) {
        String tailNo = trim(req.getTailNo());
        if (aircraftRepository.existsByTailNo(tailNo)) {
            throw new BizException("机号 " + tailNo + " 已存在，不能重复建档");
        }
        String model = trim(req.getModel()).toUpperCase();
        if (!MroDict.isValidModel(model)) {
            throw new BizException("机型只能是 B737 / A320 / A330 / ARJ21，收到的是：" + req.getModel());
        }
        // 累计值允许不传（新引进/交接中，台账缺失）：落 NULL，适航指令判定因此进入待核实；
        // 传了非负数则按录入值保存
        BigDecimal totalFh = req.getTotalFh() == null ? null : req.getTotalFh();
        Integer totalCycles = req.getTotalCycles() == null ? null : req.getTotalCycles();
        if (totalFh != null && totalFh.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("总飞行小时不能为负数（未录入请留空，不能填负数）");
        }
        if (totalCycles != null && totalCycles < 0) {
            throw new BizException("总起落次数不能为负数（未录入请留空，不能填负数）");
        }
        String airworthy = req.getAirworthy() == null || req.getAirworthy().isBlank()
                ? "AIRWORTHY"
                : req.getAirworthy().trim().toUpperCase();
        if (!MroDict.isValidAirworthy(airworthy)) {
            throw new BizException("适航状态只能是 AIRWORTHY / GROUNDED / INSPECTION / STORED，收到的是："
                    + req.getAirworthy());
        }
        Aircraft aircraft = new Aircraft();
        aircraft.setTailNo(tailNo);
        aircraft.setModel(model);
        aircraft.setAirline(trim(req.getAirline()));
        aircraft.setTotalFh(totalFh);
        aircraft.setTotalCycles(totalCycles);
        aircraft.setAirworthy(airworthy);
        aircraft.setNote(req.getNote());
        aircraft.setUpdatedAt(LocalDateTime.now());
        return toView(aircraftRepository.save(aircraft));
    }

    /** 适航状态流转：封存机解封、停场待修转定检都走这里 */
    @Transactional
    public Views.AircraftView changeAirworthy(Long id, AircraftDtos.AirworthyReq req) {
        Aircraft aircraft = aircraftRepository.findById(id)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + id));
        String target = req.getAirworthy().trim().toUpperCase();
        if (!MroDict.isValidAirworthy(target)) {
            throw new BizException("适航状态只能是 AIRWORTHY / GROUNDED / INSPECTION / STORED，收到的是："
                    + req.getAirworthy());
        }
        if (target.equals(aircraft.getAirworthy())) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已经是"
                    + MroDict.airworthyName(target) + "状态，无需重复设置");
        }
        String oldStatus = aircraft.getAirworthy();
        aircraft.setAirworthy(target);
        aircraft.setUpdatedAt(LocalDateTime.now());
        aircraft = aircraftRepository.save(aircraft);
        // 适航状态是放行证据快照的核心事实：待审放行立即失效，不能用旧快照放行
        releaseService.invalidateOnFactChange(aircraft.getId(),
                "飞机适航状态已由「" + MroDict.airworthyName(oldStatus)
                        + "」变为「" + MroDict.airworthyName(target) + "」");
        return toView(aircraft);
    }

    /** 登记一次飞行：累加飞行小时与起落次数 */
    @Transactional
    public Views.AircraftView logFlight(Long id, AircraftDtos.FlightLogReq req) {
        Aircraft aircraft = aircraftRepository.findById(id)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + id));
        if (MroDict.AIRWORTHY_STORED.equals(aircraft.getAirworthy())) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已封存，不能登记飞行");
        }
        BigDecimal hour = req.getFlightHour() == null ? BigDecimal.ZERO : req.getFlightHour();
        Integer cycles = req.getCycles() == null ? 0 : req.getCycles();
        if (hour.compareTo(BigDecimal.ZERO) <= 0 || cycles <= 0) {
            throw new BizException("本次飞行小时与起落次数都必须大于 0");
        }
        // 累计值允许为 NULL（引进交接、尚未录入）：登记第一次飞行即把累计值从“缺失”变为已知
        BigDecimal baseFh = aircraft.getTotalFh() == null ? BigDecimal.ZERO : aircraft.getTotalFh();
        int baseCycles = aircraft.getTotalCycles() == null ? 0 : aircraft.getTotalCycles();
        aircraft.setTotalFh(baseFh.add(hour));
        aircraft.setTotalCycles(baseCycles + cycles);
        aircraft.setUpdatedAt(LocalDateTime.now());
        aircraft = aircraftRepository.save(aircraft);
        // 适航指令：只重算受影响这一架飞机的当前生效清单（与本次登记同一事务提交）
        adComplianceCoordinator.onAircraftFactsChanged(aircraft.getId(),
                "飞机登记飞行：总飞行小时 +" + hour.stripTrailingZeros().toPlainString()
                        + "，总起落次数 +" + cycles + "，按最新累计值重算该机适用指令清单");
        return toView(aircraft);
    }

    private Views.AircraftView toView(Aircraft aircraft) {
        WorkCardSummary summary = summaryRepository.findById(aircraft.getId()).orElse(null);
        String bayNo = null;
        List<Bay> bays = bayRepository.findAllByCurrentAircraftId(aircraft.getId());
        if (!bays.isEmpty()) {
            bayNo = bays.get(0).getBayNo();
        }
        return new Views.AircraftView(
                aircraft.getId(),
                aircraft.getTailNo(),
                aircraft.getModel(),
                aircraft.getAirline(),
                aircraft.getTotalFh(),
                aircraft.getTotalCycles(),
                aircraft.getAirworthy(),
                MroDict.airworthyName(aircraft.getAirworthy()),
                aircraft.getNote(),
                summary == null ? 0L : nullSafe(summary.getTotalCard()),
                summary == null ? 0L : nullSafe(summary.getClosedCard()),
                summary == null ? 0L : nullSafe(summary.getOpenCard()),
                summary == null || summary.getTotalHour() == null ? BigDecimal.ZERO : summary.getTotalHour(),
                bayNo,
                aircraft.getUpdatedAt());
    }

    private long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
