package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.dto.BayDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.BayRepository;

/**
 * 模块一：机库机位。
 *
 * <p>本模块的规则面：
 * <ol>
 *   <li>机位编号全局唯一</li>
 *   <li>尺寸等级门禁：C 类只停支线机 ARJ21，B 类停不了 A330</li>
 *   <li>停用机位禁入位；有机在位时不允许停用</li>
 *   <li>一个机位只停一架、一架飞机只占一个机位</li>
 *   <li>入位/出位会连带改飞机的适航状态（进机库 → 定检中；出位 → 恢复适航）</li>
 *   <li>停场待修（GROUNDED）的飞机排故未完成，不允许出位</li>
 * </ol>
 */
@Service
public class BayService {

    private final BayRepository bayRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;

    public BayService(BayRepository bayRepository, AircraftRepository aircraftRepository,
                      ReleaseService releaseService) {
        this.bayRepository = bayRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
    }

    @Transactional(readOnly = true)
    public List<Views.BayView> list() {
        List<Views.BayView> list = new ArrayList<>();
        for (Bay bay : bayRepository.findAllByOrderByBayNoAsc()) {
            list.add(toView(bay));
        }
        return list;
    }

    @Transactional
    public Views.BayView create(BayDtos.BaySaveReq req) {
        String bayNo = trim(req.getBayNo());
        if (bayRepository.existsByBayNo(bayNo)) {
            throw new BizException("机位编号 " + bayNo + " 已存在，不能重复建");
        }
        String sizeClass = trim(req.getSizeClass()).toUpperCase();
        if (!MroDict.isValidSizeClass(sizeClass)) {
            throw new BizException("尺寸等级只能是 A / B / C，收到的是：" + req.getSizeClass());
        }
        String fitType = req.getFitType() == null || req.getFitType().isBlank()
                ? MroDict.fitTypeOf(sizeClass)
                : req.getFitType().trim();
        if (fitType.isBlank()) {
            throw new BizException("可承载机型不能为空");
        }
        // 机位长度默认值按尺寸等级给，不写在实体字段上
        BigDecimal lengthM = req.getLengthM() == null ? defaultLength(sizeClass) : req.getLengthM();
        if (lengthM.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("机位长度必须大于 0");
        }
        String status = req.getStatus() == null || req.getStatus().isBlank()
                ? "IDLE"
                : req.getStatus().trim().toUpperCase();
        if (!MroDict.isValidBayStatus(status) || "OCCUPIED".equals(status)) {
            throw new BizException("新建机位只能是 IDLE（空闲）或 DISABLED（停用），占用请走入位操作");
        }
        Bay bay = new Bay();
        bay.setBayNo(bayNo);
        bay.setSizeClass(sizeClass);
        bay.setFitType(fitType);
        bay.setLengthM(lengthM);
        bay.setStatus(status);
        bay.setCurrentAircraftId(null);
        bay.setUpdatedAt(LocalDateTime.now());
        return toView(bayRepository.save(bay));
    }

    /** 安排飞机入位 */
    @Transactional
    public Views.BayView occupy(Long bayId, BayDtos.OccupyReq req) {
        Bay bay = bayRepository.findById(bayId)
                .orElseThrow(() -> new BizException("机位不存在：id=" + bayId));
        if ("DISABLED".equals(bay.getStatus())) {
            throw new BizException("机位 " + bay.getBayNo() + " 已停用，不能安排飞机入位");
        }
        if ("OCCUPIED".equals(bay.getStatus()) || bay.getCurrentAircraftId() != null) {
            throw new BizException("机位 " + bay.getBayNo() + " 已被占用，一个机位只能停一架飞机");
        }
        Long aircraftId = req == null ? null : req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择要入位的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        List<Bay> occupied = bayRepository.findAllByCurrentAircraftId(aircraftId);
        if (!occupied.isEmpty()) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已在机位 "
                    + occupied.get(0).getBayNo() + "，一架飞机只能占一个机位");
        }
        if (!MroDict.bayFits(bay.getSizeClass(), aircraft.getModel())) {
            throw new BizException(MroDict.sizeClassName(bay.getSizeClass()) + "机位 " + bay.getBayNo()
                    + " 承载不了 " + aircraft.getModel() + "，可承载机型为 " + bay.getFitType());
        }
        bay.setStatus("OCCUPIED");
        bay.setCurrentAircraftId(aircraft.getId());
        bay.setUpdatedAt(LocalDateTime.now());
        // 连带规则：适航机进机库定检，自动转为"定检中"
        if ("AIRWORTHY".equals(aircraft.getAirworthy())) {
            aircraft.setAirworthy("INSPECTION");
            aircraft.setUpdatedAt(LocalDateTime.now());
            aircraftRepository.save(aircraft);
            // 机位占用/适航状态变了：该机若还有卡在审签链上的旧放行（理论上出位已消费），立即失效
            releaseService.invalidateOnFactChange(aircraft.getId(),
                    "飞机重新进入机位 " + bay.getBayNo() + "，适航状态变更，开始新一轮维修");
        }
        return toView(bayRepository.save(bay));
    }

    /**
     * 飞机出位（出厂）。
     *
     * <p>模块六接入后，出位的唯一合法凭据是"当前这一轮维修事实"对应的、质量终审已通过
     * 且未消费的放行记录；没有记录 / 已失效 / 快照对不上当前事实，统一在此阻断，
     * 与放行页门禁提示完全一致。校验与消费放行与机位更新在同一事务里完成。
     */
    @Transactional
    public Views.BayView release(Long bayId) {
        Bay bay = bayRepository.findById(bayId)
                .orElseThrow(() -> new BizException("机位不存在：id=" + bayId));
        if (!"OCCUPIED".equals(bay.getStatus()) || bay.getCurrentAircraftId() == null) {
            throw new BizException("机位 " + bay.getBayNo() + " 本来就是空的，无需出位");
        }
        Aircraft aircraft = aircraftRepository.findById(bay.getCurrentAircraftId()).orElse(null);
        if (aircraft != null && MroDict.AIRWORTHY_GROUNDED.equals(aircraft.getAirworthy())) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 处于停场待修，排故未闭环不得出厂");
        }
        // 出厂放行门禁：没有效已放行记录 / 已失效 / 不是当前维修事实，全部在此被拦
        var releaseOrder = releaseService.checkDepartureGate(bay, aircraft);

        bay.setStatus("IDLE");
        Long aircraftId = bay.getCurrentAircraftId();
        bay.setCurrentAircraftId(null);
        bay.setUpdatedAt(LocalDateTime.now());
        if (aircraft != null && "INSPECTION".equals(aircraft.getAirworthy())) {
            aircraft.setAirworthy("AIRWORTHY");
            aircraft.setUpdatedAt(LocalDateTime.now());
            aircraftRepository.save(aircraft);
        }
        Views.BayView view = toView(bayRepository.save(bay));
        // 消费放行：标记已出位并写入审计（同一事务，并发第二个出位会在此被挡）
        releaseService.markDeparted(releaseOrder, bay.getBayNo());
        // 机位占用已解除，若仍有同机待审记录（正常不会有，防御性处理）立即失效
        releaseService.invalidateOnFactChange(aircraftId, "飞机已从机位出位，机位占用解除");
        return view;
    }

    /** 机位状态：启用 / 停用 */
    @Transactional
    public Views.BayView changeStatus(Long bayId, BayDtos.BayStatusReq req) {
        Bay bay = bayRepository.findById(bayId)
                .orElseThrow(() -> new BizException("机位不存在：id=" + bayId));
        String status = req.getStatus().trim().toUpperCase();
        if (!MroDict.isValidBayStatus(status)) {
            throw new BizException("机位状态只能是 IDLE / OCCUPIED / DISABLED，收到的是：" + req.getStatus());
        }
        if ("OCCUPIED".equals(status)) {
            throw new BizException("占用状态只能通过「安排入位」操作设置");
        }
        if ("DISABLED".equals(status) && bay.getCurrentAircraftId() != null) {
            throw new BizException("机位 " + bay.getBayNo() + " 上还有飞机，请先安排出位再停用");
        }
        bay.setStatus(status);
        bay.setUpdatedAt(LocalDateTime.now());
        return toView(bayRepository.save(bay));
    }

    private BigDecimal defaultLength(String sizeClass) {
        return switch (sizeClass) {
            case "A" -> BigDecimal.valueOf(60.0);
            case "B" -> BigDecimal.valueOf(44.0);
            default -> BigDecimal.valueOf(34.0);
        };
    }

    public Views.BayView toView(Bay bay) {
        String tailNo = null;
        String model = null;
        String airworthyName = null;
        if (bay.getCurrentAircraftId() != null) {
            Optional<Aircraft> aircraft = aircraftRepository.findById(bay.getCurrentAircraftId());
            if (aircraft.isPresent()) {
                tailNo = aircraft.get().getTailNo();
                model = aircraft.get().getModel();
                airworthyName = MroDict.airworthyName(aircraft.get().getAirworthy());
            }
        }
        return new Views.BayView(bay.getId(), bay.getBayNo(), bay.getSizeClass(),
                MroDict.sizeClassName(bay.getSizeClass()), bay.getFitType(), bay.getLengthM(),
                bay.getStatus(), MroDict.bayStatusName(bay.getStatus()), bay.getCurrentAircraftId(),
                tailNo, model, airworthyName, bay.getUpdatedAt());
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
