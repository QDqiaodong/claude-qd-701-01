package com.aircraft.mro.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.dto.DefectDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.DefectRepository;

/**
 * 模块四：故障保留。
 *
 * <p>规则面：
 * <ol>
 *   <li>缺陷单号唯一</li>
 *   <li>保留期限：DAY 按飞行日直接加天；HOUR 按每天飞 {@value MroDict#HOUR_PER_DAY} 小时折算成飞行日</li>
 *   <li>到期日已过且仍开放的，列表时自动置为超期（OVERDUE）</li>
 *   <li>已关闭的缺陷不能重复关闭</li>
 * </ol>
 */
@Service
public class DefectService {

    private final DefectRepository defectRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseService releaseService;

    public DefectService(DefectRepository defectRepository, AircraftRepository aircraftRepository,
                         ReleaseService releaseService) {
        this.defectRepository = defectRepository;
        this.aircraftRepository = aircraftRepository;
        this.releaseService = releaseService;
    }

    @Transactional
    public List<Views.DefectView> list(Long aircraftId) {
        List<Defect> defects = aircraftId == null
                ? defectRepository.findAllByOrderByDefectNoAsc()
                : defectRepository.findAllByAircraftIdOrderByDefectNoAsc(aircraftId);
        LocalDate today = LocalDate.now();
        // 超期自动置位：开放中且到期日已过的，落库成 OVERDUE，页面整行标红
        for (Defect defect : defects) {
            if ("OPEN".equals(defect.getStatus()) && defect.getDueDate().isBefore(today)) {
                defect.setStatus("OVERDUE");
                defect.setUpdatedAt(LocalDateTime.now());
                defectRepository.save(defect);
                // 故障状态变化（开放→超期）：该机待审放行立即失效，不能拿旧快照放行
                releaseService.invalidateOnFactChange(defect.getAircraftId(),
                        "故障 " + defect.getDefectNo() + " 已超过保留到期日，变为超期故障");
            }
        }
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defects) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<Views.DefectView> overdue() {
        Map<Long, String> tailMap = new HashMap<>();
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            tailMap.put(aircraft.getId(), aircraft.getTailNo());
        }
        LocalDate today = LocalDate.now();
        List<Views.DefectView> list = new ArrayList<>();
        for (Defect defect : defectRepository.findAllByStatusOrderByDueDateAsc("OVERDUE")) {
            list.add(toView(defect, tailMap, today));
        }
        return list;
    }

    @Transactional
    public Views.DefectView create(DefectDtos.DefectSaveReq req) {
        String defectNo = trim(req.getDefectNo());
        if (defectRepository.existsByDefectNo(defectNo)) {
            throw new BizException("缺陷单号 " + defectNo + " 已存在，不能重复登记");
        }
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择缺陷所属的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        String unit = trim(req.getLimitUnit()).toUpperCase();
        if (!MroDict.isValidLimitUnit(unit)) {
            throw new BizException("保留期限单位只能是 DAY（飞行日）或 HOUR（飞行小时），收到的是：" + req.getLimitUnit());
        }
        Integer limitValue = req.getLimitValue() == null ? 0 : req.getLimitValue();
        if (limitValue <= 0) {
            throw new BizException("保留期限数值必须大于 0");
        }
        int days = MroDict.limitToDays(unit, limitValue);
        if (days > MroDict.MAX_LIMIT_DAY) {
            throw new BizException("保留期限折算后为 " + days + " 天，超过上限 " + MroDict.MAX_LIMIT_DAY + " 天，需走工程评估");
        }
        LocalDate foundDate = req.getFoundDate() == null ? LocalDate.now() : req.getFoundDate();
        LocalDate dueDate = foundDate.plusDays(days);
        Defect defect = new Defect();
        defect.setDefectNo(defectNo);
        defect.setAircraftId(aircraft.getId());
        defect.setAtaChapter(trim(req.getAtaChapter()));
        defect.setDescription(trim(req.getDescription()));
        defect.setLimitUnit(unit);
        defect.setLimitValue(limitValue);
        defect.setFoundDate(foundDate);
        defect.setDueDate(dueDate);
        defect.setStatus(dueDate.isBefore(LocalDate.now()) ? "OVERDUE" : "OPEN");
        defect.setReporter(req.getReporter() == null || req.getReporter().isBlank() ? null : req.getReporter().trim());
        defect.setUpdatedAt(LocalDateTime.now());
        defect = defectRepository.save(defect);
        // 新增关联故障：待审放行的证据快照对不上当前事实，立即失效
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "新增故障保留 " + defect.getDefectNo() + "（" + MroDict.defectStatusName(defect.getStatus()) + "）");
        return toView(defect, tailNoMap(aircraft), LocalDate.now());
    }

    /** 关闭缺陷：已关闭的不能再关 */
    @Transactional
    public Views.DefectView close(Long id) {
        Defect defect = defectRepository.findById(id)
                .orElseThrow(() -> new BizException("缺陷单不存在：id=" + id));
        if ("CLOSED".equals(defect.getStatus())) {
            throw new BizException("缺陷单 " + defect.getDefectNo() + " 已关闭，不能重复关闭");
        }
        defect.setStatus("CLOSED");
        defect.setUpdatedAt(LocalDateTime.now());
        defect = defectRepository.save(defect);
        // 保留故障被闭环，也是维修事实变化：待审放行立即失效（差异视图可查）
        releaseService.invalidateOnFactChange(defect.getAircraftId(),
                "故障 " + defect.getDefectNo() + " 已关闭，维修事实变化");
        return toView(defect, tailNoMap(null), LocalDate.now());
    }

    private Views.DefectView toView(Defect defect, Map<Long, String> tailMap, LocalDate today) {
        boolean overdue = !"CLOSED".equals(defect.getStatus()) && defect.getDueDate().isBefore(today);
        long daysLeft = ChronoUnit.DAYS.between(today, defect.getDueDate());
        return new Views.DefectView(defect.getId(), defect.getDefectNo(), defect.getAircraftId(),
                tailMap.get(defect.getAircraftId()), defect.getAtaChapter(), defect.getDescription(),
                defect.getLimitUnit(), MroDict.limitUnitName(defect.getLimitUnit()), defect.getLimitValue(),
                defect.getLimitValue() + MroDict.limitUnitName(defect.getLimitUnit()),
                defect.getFoundDate(), defect.getDueDate(), defect.getStatus(),
                MroDict.defectStatusName(defect.getStatus()), defect.getReporter(), daysLeft, overdue);
    }

    private Map<Long, String> tailNoMap(Aircraft only) {
        Map<Long, String> map = new HashMap<>();
        if (only != null) {
            map.put(only.getId(), only.getTailNo());
            return map;
        }
        for (Aircraft aircraft : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(aircraft.getId(), aircraft.getTailNo());
        }
        return map;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
