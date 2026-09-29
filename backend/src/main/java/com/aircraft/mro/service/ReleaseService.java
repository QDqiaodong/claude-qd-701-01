package com.aircraft.mro.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.Bay;
import com.aircraft.mro.domain.Defect;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.ReleaseEvent;
import com.aircraft.mro.domain.ReleaseOrder;
import com.aircraft.mro.domain.ReleaseSnapshot;
import com.aircraft.mro.domain.ReleaseSnapshotCard;
import com.aircraft.mro.domain.ReleaseSnapshotDefect;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.dto.ReleaseDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.ReleaseEventRepository;
import com.aircraft.mro.repository.ReleaseOrderRepository;
import com.aircraft.mro.repository.ReleaseSnapshotCardRepository;
import com.aircraft.mro.repository.ReleaseSnapshotDefectRepository;
import com.aircraft.mro.repository.ReleaseSnapshotRepository;

/**
 * 模块六：出厂放行审签。
 *
 * <p>一条审签 = 单据头（{@link ReleaseOrder}）+ 版本化且不可变的证据快照
 * （{@link ReleaseSnapshot} 及两张明细表）+ append-only 审计事件（{@link ReleaseEvent}）。
 *
 * <p>规则面（全部服务端强制，前端只做展示）：
 * <ol>
 *   <li>发起/提交门禁：飞机必须适航、不得有未闭环工卡、不得有超期故障；未超期的开放故障
 *       允许保留，但必须逐条写保留依据，并由质量终审逐条确认。</li>
 *   <li>流转：草稿 → 待机械放行 → 待质量终审 → 已放行；驳回留原因并回到可修订状态；
 *       再次提交生成新版本快照。</li>
 *   <li>发起人 / 机械放行人 / 质量终审人必须三人互不相同，谁也不能替自己完成下一环节。</li>
 *   <li>审签链上（待机械放行/待质量终审）只要工卡、故障、适航状态、机位占用在快照之后
 *       发生变化，立即失效，已有签字保留作审计但不得沿用，必须重新发起。</li>
 *   <li>终审通过后才允许出位；没有有效已放行记录、已失效、或放行对应的不是当前这一轮
 *       维修事实（快照对不上）时，出位入口统一阻断并给出同一口径的提示。</li>
 *   <li>并发：审签行悲观锁串行化 + active_aircraft_id 唯一占位。重复点击同一动作幂等
 *       返回同一结果；互相冲突的动作只有一个成功，另一个被告知当前状态。</li>
 * </ol>
 */
@Service
public class ReleaseService {

    /** 失效标记：系统自动（上游事实变化） */
    public static final String INVALID_BY_SYSTEM = "SYSTEM";

    private final ReleaseOrderRepository orderRepository;
    private final ReleaseSnapshotRepository snapshotRepository;
    private final ReleaseSnapshotCardRepository cardItemRepository;
    private final ReleaseSnapshotDefectRepository defectItemRepository;
    private final ReleaseEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final ReleaseFactsCollector factsCollector;
    private final ReleaseService self;

    public ReleaseService(ReleaseOrderRepository orderRepository,
                          ReleaseSnapshotRepository snapshotRepository,
                          ReleaseSnapshotCardRepository cardItemRepository,
                          ReleaseSnapshotDefectRepository defectItemRepository,
                          ReleaseEventRepository eventRepository,
                          AircraftRepository aircraftRepository,
                          ReleaseFactsCollector factsCollector,
                          @org.springframework.context.annotation.Lazy ReleaseService self) {
        this.orderRepository = orderRepository;
        this.snapshotRepository = snapshotRepository;
        this.cardItemRepository = cardItemRepository;
        this.defectItemRepository = defectItemRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.factsCollector = factsCollector;
        this.self = self;
    }

    // ============================== 读：总览 / 门禁 / 列表 / 详情 ==============================

    @Transactional(readOnly = true)
    public Views.ReleaseOverview overview(Long currentAircraftId) {
        long pending = countPending();
        long invalid = orderRepository.countByStatus(MroDict.REL_INVALID);
        long released = orderRepository.countByStatus(MroDict.REL_RELEASED);
        long draft = orderRepository.countByStatus(MroDict.REL_DRAFT);
        long rejected = orderRepository.countByStatus(MroDict.REL_REJECTED);
        Long pendingId = null;
        String gateMessage = null;
        if (currentAircraftId != null) {
            List<ReleaseOrder> active = orderRepository.findAllByActiveAircraftId(currentAircraftId);
            if (!active.isEmpty()) {
                ReleaseOrder order = active.get(0);
                if (MroDict.RELEASE_PENDING_STATUSES.contains(order.getStatus())
                        || MroDict.REL_DRAFT.equals(order.getStatus())) {
                    pendingId = order.getId();
                }
            }
            gateMessage = gate(currentAircraftId).message();
        }
        return new Views.ReleaseOverview(pending, invalid, released, draft, rejected, pendingId, gateMessage);
    }

    /** 待机械放行 + 待质量终审（顶部"待审"口径） */
    private long countPending() {
        return orderRepository.countByStatus(MroDict.REL_WAIT_MECH)
                + orderRepository.countByStatus(MroDict.REL_WAIT_QA);
    }

    /**
     * 发起/提交门禁检查：唯一口径来源。
     * 放行模块页面、机位出位（{@link #checkDepartureGate(Bay, Aircraft)}）都调它，提示一致。
     */
    @Transactional(readOnly = true)
    public Views.ReleaseGateView gate(Long aircraftId) {
        Aircraft aircraft = aircraftRepository.findById(aircraftId).orElse(null);
        if (aircraft == null) {
            throw new BizException("飞机不存在：id=" + aircraftId);
        }
        ReleaseFactsCollector.ReleaseFacts facts = factsCollector.collect(aircraftId, true);
        Bay bay = facts.bay;
        boolean airworthyOk = "AIRWORTHY".equals(aircraft.getAirworthy());
        boolean occupied = bay != null && "OCCUPIED".equals(bay.getStatus())
                && aircraftId.equals(bay.getCurrentAircraftId());
        boolean noOpenCards = facts.openCards.isEmpty();
        boolean noOverdue = facts.overdueDefects.isEmpty();

        List<String> blockers = new ArrayList<>();
        if (!airworthyOk) {
            blockers.add("飞机当前为「" + MroDict.airworthyName(aircraft.getAirworthy())
                    + "」，不是适航状态，不能进入出厂放行审签");
        }
        if (!occupied) {
            blockers.add("飞机当前不在任何占用中的机位上，无在位维修事实可放行");
        }
        if (!noOpenCards) {
            List<String> nos = facts.openCards.stream().map(WorkCard::getCardNo).toList();
            blockers.add("仍有 " + facts.openCards.size() + " 张未闭环工卡（" + String.join("、", nos) + "）");
        }
        if (!noOverdue) {
            List<String> nos = facts.overdueDefects.stream().map(Defect::getDefectNo).toList();
            blockers.add("存在 " + facts.overdueDefects.size() + " 条超期故障（" + String.join("、", nos) + "），禁止放行");
        }
        String message;
        if (blockers.isEmpty()) {
            message = facts.openDefects.isEmpty()
                    ? "门禁通过：飞机适航、机位在位、工卡全部闭环、无超期故障，可以发起放行"
                    : "门禁通过（带保留故障 " + facts.openDefects.size()
                            + " 条）：提交时必须逐条写明保留依据并经质量终审确认";
        } else {
            message = "不能进入出厂放行审签：" + String.join("；", blockers);
        }
        return new Views.ReleaseGateView(aircraftId, aircraft.getTailNo(), aircraft.getModel(),
                aircraft.getAirworthy(), MroDict.airworthyName(aircraft.getAirworthy()), airworthyOk,
                bay == null ? null : bay.getId(), bay == null ? null : bay.getBayNo(),
                bay == null ? null : bay.getStatus(),
                bay == null ? "" : MroDict.bayStatusName(bay.getStatus()),
                occupied,
                facts.openCards.size(), facts.overdueDefects.size(), facts.openDefects.size(),
                blockers.isEmpty(), message, blockers);
    }

    @Transactional(readOnly = true)
    public List<Views.ReleaseListItem> list(Long aircraftId) {
        List<ReleaseOrder> orders = aircraftId == null
                ? orderRepository.findAllByOrderByCreatedAtDesc()
                : orderRepository.findAllByAircraftIdOrderByCreatedAtDesc(aircraftId);
        Map<Long, Aircraft> aircraftMap = new HashMap<>();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            aircraftMap.put(a.getId(), a);
        }
        List<Views.ReleaseListItem> list = new ArrayList<>();
        for (ReleaseOrder order : orders) {
            Aircraft aircraft = aircraftMap.get(order.getAircraftId());
            ReleaseSnapshot snap = snapshotRepository
                    .findFirstByReleaseIdOrderByVersionNoDesc(order.getId()).orElse(null);
            list.add(new Views.ReleaseListItem(
                    order.getId(), order.getReleaseNo(), order.getAircraftId(),
                    aircraft == null ? "?" : aircraft.getTailNo(),
                    aircraft == null ? "?" : aircraft.getModel(),
                    order.getStatus(), MroDict.releaseStatusName(order.getStatus()),
                    order.getInitiator(), order.getMechSigner(), order.getQaSigner(),
                    snap == null ? null : snap.getVersionNo(),
                    snap == null ? 0 : snap.getOpenCardCount(),
                    snap == null ? 0 : snap.getOpenDefectCount(),
                    snap == null ? 0 : snap.getOverdueDefectCount(),
                    order.getInvalidReason(), order.getSubmittedAt(), order.getQaSignedAt(),
                    order.getDepartedAt(), order.getCreatedAt()));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public Views.ReleaseDetail detail(Long id) {
        ReleaseOrder order = requireOrder(id);
        Aircraft aircraft = aircraftRepository.findById(order.getAircraftId())
                .orElseThrow(() -> new BizException("飞机台账缺失：id=" + order.getAircraftId()));
        List<ReleaseSnapshot> snapshots = snapshotRepository.findAllByReleaseIdOrderByVersionNoAsc(id);
        int latestVersion = snapshots.isEmpty() ? 0 : snapshots.get(snapshots.size() - 1).getVersionNo();

        // 当前事实（用于差异比对 + 门禁面板）
        ReleaseFactsCollector.ReleaseFacts facts = factsCollector.collect(order.getAircraftId(), true);
        Map<Long, WorkCard> cardMap = new HashMap<>();
        for (WorkCard c : facts.cards) {
            cardMap.put(c.getId(), c);
        }
        Map<Long, Defect> defectMap = new HashMap<>();
        for (Defect d : facts.defects) {
            defectMap.put(d.getId(), d);
        }

        List<Views.SnapshotView> snapshotViews = new ArrayList<>();
        for (ReleaseSnapshot snap : snapshots) {
            snapshotViews.add(toSnapshotView(snap, latestVersion, cardMap, defectMap, facts.today));
        }

        List<Views.ReleaseEventView> events = new ArrayList<>();
        for (ReleaseEvent e : eventRepository.findAllByReleaseIdOrderByEventAtAscIdAsc(id)) {
            events.add(new Views.ReleaseEventView(e.getId(), e.getEventType(),
                    eventName(e.getEventType()), e.getOperator(), e.getDetail(),
                    e.getSnapshotVersion(), e.getEventAt()));
        }

        boolean editable = MroDict.REL_DRAFT.equals(order.getStatus())
                || MroDict.REL_REJECTED.equals(order.getStatus());
        return new Views.ReleaseDetail(
                order.getId(), order.getReleaseNo(), order.getAircraftId(),
                aircraft.getTailNo(), aircraft.getModel(), aircraft.getAirline(),
                order.getStatus(), MroDict.releaseStatusName(order.getStatus()),
                order.getInitiator(), order.getMechSigner(), order.getQaSigner(),
                order.getSubmittedAt(), order.getMechSignedAt(), order.getQaSignedAt(),
                order.getRejectReason(), order.getInvalidReason(), order.getInvalidatedAt(),
                order.getDepartedAt(), order.getDepartedBayNo(),
                order.getCreatedAt(), order.getUpdatedAt(), editable,
                gate(order.getAircraftId()), snapshotViews, events);
    }

    // ============================== 发起（草稿 + v1 快照） ==============================

    @Transactional
    public Views.ReleaseDetail create(ReleaseDtos.CreateReq req) {
        Long aircraftId = req.getAircraftId();
        if (aircraftId == null) {
            throw new BizException("请先选择要发起出厂放行的飞机");
        }
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        String initiator = trim(req.getInitiator());
        if (initiator.isEmpty()) {
            throw new BizException("发起人姓名不能为空");
        }

        // 门禁：不是适航 / 有未闭环工卡 / 有超期故障，直接不让进入审签
        Views.ReleaseGateView gate = gate(aircraftId);
        if (!gate.allowed()) {
            throw new BizException(gate.message());
        }
        // 同机同时只能有一条有效审签（数据库 UNIQUE 再兜底一次）
        if (!orderRepository.findAllByActiveAircraftId(aircraftId).isEmpty()) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已存在进行中的放行审签，不能重复发起");
        }

        LocalDateTime now = LocalDateTime.now();
        ReleaseOrder order = new ReleaseOrder();
        order.setReleaseNo(nextReleaseNo());
        order.setAircraftId(aircraftId);
        order.setActiveAircraftId(aircraftId);
        order.setStatus(MroDict.REL_DRAFT);
        order.setInitiator(initiator);
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        try {
            order = orderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 已存在进行中的放行审签（并发占用），请刷新后重试");
        }
        // v1 证据快照（草稿版）
        buildSnapshot(order, 1, "DRAFT_SNAPSHOT", Map.of(), now);
        event(order, "CREATE", initiator,
                "发起出厂放行审签，已生成 v1 证据快照（草稿）。" + gate.message(), 1, now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        return detail(order.getId());
    }

    // ============================== 修订保留依据（草稿/已驳回） ==============================

    @Transactional
    public Views.ReleaseDetail saveRetainBasis(Long id, ReleaseDtos.RetainBasisReq req) {
        ReleaseOrder order = lockOrder(id);
        requireEditable(order);
        String operator = trim(req.getOperator());
        if (!operator.equals(order.getInitiator())) {
            throw new BizException("只有发起人「" + order.getInitiator() + "」可以修订保留依据，收到的操作人是：" + operator);
        }
        ReleaseSnapshot snap = latestSnapshot(id);
        ReleaseSnapshotDefect item = defectItemRepository.findById(req.getSnapshotDefectId() == null ? -1L : req.getSnapshotDefectId())
                .orElseThrow(() -> new BizException("快照故障条目不存在：id=" + req.getSnapshotDefectId()));
        if (!item.getSnapshotId().equals(snap.getId())) {
            throw new BizException("只能修订当前版本快照（v" + snap.getVersionNo() + "）里的保留依据");
        }
        if ("CLOSED".equals(item.getStatusCode())) {
            throw new BizException("该故障已闭环，无需保留依据");
        }
        String basis = trim(req.getRetainBasis());
        if (basis.isEmpty()) {
            throw new BizException("开放故障的保留依据不能为空");
        }
        item.setRetainBasis(basis);
        // 依据改过之后需要质量终审重新确认
        item.setQaConfirmedFlag(0);
        defectItemRepository.save(item);
        LocalDateTime now = LocalDateTime.now();
        event(order, "RETAIN", operator,
                "填写/修订故障 " + item.getDefectNo() + " 的保留依据：" + basis,
                snap.getVersionNo(), now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        return detail(id);
    }

    // ============================== 提交：草稿/已驳回 → 待机械放行 ==============================

    @Transactional
    public Views.ReleaseActionResult submit(Long id, ReleaseDtos.SubmitReq req) {
        ReleaseOrder order = lockOrder(id);
        String operator = trim(req.getOperator());
        if (operator.isEmpty()) {
            throw new BizException("提交人姓名不能为空");
        }
        if (!operator.equals(order.getInitiator())) {
            throw new BizException("只有发起人「" + order.getInitiator() + "」可以提交，不能由他人代提交");
        }
        boolean resubmit = MroDict.REL_REJECTED.equals(order.getStatus());
        if (!MroDict.REL_DRAFT.equals(order.getStatus()) && !resubmit) {
            // 重复点击 / 状态已推进：幂等告知当前状态，不再生成快照
            return idempotentResult(order, "该审签当前为「" + MroDict.releaseStatusName(order.getStatus())
                    + "」，无需重复提交");
        }

        // 再次提交必须形成【新】证据快照（驳回后重新提交 / 草稿首次提交都重新取当前事实）
        LocalDateTime now = LocalDateTime.now();
        ReleaseFactsCollector.ReleaseFacts facts = factsCollector.collect(order.getAircraftId(), true);
        List<String> blockers = gateBlockers(facts);
        if (!blockers.isEmpty()) {
            throw new BizException("提交被门禁拦截：" + String.join("；", blockers)
                    + "。请处理后重新提交（本次不会产生新版本快照）");
        }

        int nextVersion = (int) snapshotRepository.countByReleaseId(id) + 1;
        // 继承上一版已填的保留依据（同一故障没闭环的话沿用，可在提交后被驳回再改）
        Map<Long, String> basisMap = inheritRetainBasis(id);
        ReleaseSnapshot snap = buildSnapshot(order, nextVersion, "SUBMIT_SNAPSHOT", basisMap, now);

        // 开放故障必须逐条有保留依据，否则不允许提交
        List<String> missing = new ArrayList<>();
        List<ReleaseSnapshotDefect> defectItems = defectItemRepository.findAllBySnapshotIdOrderByDefectNoAsc(snap.getId());
        for (ReleaseSnapshotDefect item : defectItems) {
            if (item.getOverdueFlag() == 0 && (item.getRetainBasis() == null || item.getRetainBasis().isBlank())) {
                missing.add(item.getDefectNo());
            }
        }
        if (!missing.isEmpty()) {
            // 快照已建（证据留痕），但不允许进入待机械放行；删除刚建的快照会破坏不可变原则，
            // 因此保留快照并回退单据为草稿，让用户补完依据后重新提交（会再出一版快照）。
            order.setStatus(MroDict.REL_DRAFT);
            order.setActiveAircraftId(order.getAircraftId());
            order.setUpdatedAt(now);
            orderRepository.save(order);
            event(order, "SUBMIT_BLOCKED", operator,
                    "提交被拦：开放故障 " + String.join("、", missing) + " 未逐条写明保留依据（v"
                            + nextVersion + " 快照已留存）", nextVersion, now);
            throw new BizException("开放故障 " + String.join("、", missing)
                    + " 必须逐条写明保留依据后才能提交");
        }

        order.setStatus(MroDict.REL_WAIT_MECH);
        order.setActiveAircraftId(order.getAircraftId());
        order.setSubmittedAt(now);
        order.setRejectReason(null);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, "SUBMIT", operator,
                (resubmit ? "驳回修订后重新提交，" : "") + "提交进入待机械放行，已生成 v"
                        + nextVersion + " 证据快照", nextVersion, now);
        return new Views.ReleaseActionResult(order.getId(), order.getReleaseNo(), order.getStatus(),
                MroDict.releaseStatusName(order.getStatus()), false,
                "已提交，等待机械放行（证据快照 v" + nextVersion + "）");
    }

    // ============================== 机械放行 ==============================

    @Transactional
    public Views.ReleaseActionResult mechSign(Long id, ReleaseDtos.SignReq req) {
        ReleaseOrder order = lockOrder(id);
        String signer = trim(req.getSigner());
        if (signer.isEmpty()) {
            throw new BizException("机械放行人姓名不能为空");
        }
        if (MroDict.REL_WAIT_QA.equals(order.getStatus())) {
            return idempotentResult(order, signer.equals(order.getMechSigner())
                    ? "你已完成机械放行，该审签当前为「待质量终审」，请勿重复签字"
                    : "该审签已由「" + order.getMechSigner() + "」完成机械放行，当前为「待质量终审」");
        }
        if (!MroDict.REL_WAIT_MECH.equals(order.getStatus())) {
            throw new BizException("机械放行被拒：审签当前为「" + MroDict.releaseStatusName(order.getStatus())
                    + "」，只有「待机械放行」状态可以签字");
        }
        if (signer.equals(order.getInitiator())) {
            throw new BizException("机械放行人不能与发起人为同一人（" + signer + "），任何人都不能替自己完成下一环节");
        }
        // 签字前复核：快照之后上游已变 → 立即失效，不能用旧快照放行
        String drift = detectDrift(order);
        if (drift != null) {
            invalidate(order, drift, INVALID_BY_SYSTEM);
            throw new BizException("机械放行未生效：" + drift + "，该审签已标记为已失效，请重新发起");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setMechSigner(signer);
        order.setMechSignedAt(now);
        order.setStatus(MroDict.REL_WAIT_QA);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, "MECH_SIGN", signer, "机械放行通过，提交质量终审", currentVersion(id), now);
        return new Views.ReleaseActionResult(order.getId(), order.getReleaseNo(), order.getStatus(),
                MroDict.releaseStatusName(order.getStatus()), false, "机械放行已签字，等待质量终审");
    }

    // ============================== 质量终审：逐条确认保留故障 ==============================

    @Transactional
    public Views.ReleaseDetail confirmRetainedDefect(Long id, ReleaseDtos.ConfirmDefectReq req) {
        ReleaseOrder order = lockOrder(id);
        String signer = trim(req.getSigner());
        if (signer.isEmpty()) {
            throw new BizException("终审人姓名不能为空");
        }
        if (signer.equals(order.getInitiator())) {
            throw new BizException("质量终审人不能与发起人为同一人（" + signer + "）");
        }
        if (order.getMechSigner() != null && signer.equals(order.getMechSigner())) {
            throw new BizException("质量终审人不能与机械放行人为同一人（" + signer + "）");
        }
        if (!MroDict.REL_WAIT_QA.equals(order.getStatus())) {
            throw new BizException("只有「待质量终审」状态可以确认保留故障，当前为「"
                    + MroDict.releaseStatusName(order.getStatus()) + "」");
        }
        ReleaseSnapshot snap = latestSnapshot(id);
        ReleaseSnapshotDefect item = defectItemRepository
                .findById(req.getSnapshotDefectId() == null ? -1L : req.getSnapshotDefectId())
                .orElseThrow(() -> new BizException("快照故障条目不存在：id=" + req.getSnapshotDefectId()));
        if (!item.getSnapshotId().equals(snap.getId())) {
            throw new BizException("只能在当前版本快照（v" + snap.getVersionNo() + "）上确认");
        }
        boolean confirmed = req.getConfirmed() == null || req.getConfirmed();
        item.setQaConfirmedFlag(confirmed ? 1 : 0);
        defectItemRepository.save(item);
        LocalDateTime now = LocalDateTime.now();
        event(order, "QA_CONFIRM", signer,
                (confirmed ? "确认故障 " + item.getDefectNo() + " 的保留依据成立"
                        : "撤回对故障 " + item.getDefectNo() + " 保留依据的确认")
                        + "（v" + snap.getVersionNo() + "）", snap.getVersionNo(), now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        return detail(id);
    }

    // ============================== 质量终审通过 ==============================

    @Transactional
    public Views.ReleaseActionResult qaApprove(Long id, ReleaseDtos.SignReq req) {
        ReleaseOrder order = lockOrder(id);
        String signer = trim(req.getSigner());
        if (signer.isEmpty()) {
            throw new BizException("质量终审人姓名不能为空");
        }
        // 重复点击同一终审动作：已经是同一人放的行 → 返回同一结果（幂等）
        if (MroDict.REL_RELEASED.equals(order.getStatus())) {
            if (signer.equals(order.getQaSigner())) {
                return idempotentResult(order, "你已完成质量终审，飞机 " + order.getReleaseNo()
                        + " 已放行（重复点击不再重复放行）");
            }
            throw new BizException("终审冲突：该审签已由「" + order.getQaSigner()
                    + "」完成质量终审并放行，你不能重复放行");
        }
        if (order.getDepartedAt() != null) {
            throw new BizException("该放行已用于出位，不能再次终审");
        }
        if (!MroDict.REL_WAIT_QA.equals(order.getStatus())) {
            throw new BizException("质量终审被拒：审签当前为「" + MroDict.releaseStatusName(order.getStatus())
                    + "」，只有「待质量终审」状态可以终审");
        }
        // 三人互不相同
        if (signer.equals(order.getInitiator())) {
            throw new BizException("质量终审人不能与发起人为同一人（" + signer + "），不能替自己完成终审");
        }
        if (signer.equals(order.getMechSigner())) {
            throw new BizException("质量终审人不能与机械放行人为同一人（" + signer + "）");
        }
        // 终审与上游变更同时发生：锁内最后复核一遍当前事实，对不上立即失效，绝不拿旧快照放行
        String drift = detectDrift(order);
        if (drift != null) {
            invalidate(order, drift, INVALID_BY_SYSTEM);
            throw new BizException("终审未生效：" + drift + "，审签已标记为已失效，已有签字保留备查，请重新发起");
        }
        ReleaseSnapshot snap = latestSnapshot(id);
        List<ReleaseSnapshotDefect> defectItems =
                defectItemRepository.findAllBySnapshotIdOrderByDefectNoAsc(snap.getId());
        List<String> unconfirmed = defectItems.stream()
                .filter(d -> d.getOverdueFlag() == 0 && d.getQaConfirmedFlag() == 0)
                .map(ReleaseSnapshotDefect::getDefectNo).toList();
        if (!unconfirmed.isEmpty()) {
            throw new BizException("质量终审不能通过：开放故障 " + String.join("、", unconfirmed)
                    + " 的保留依据尚未逐条确认");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setQaSigner(signer);
        order.setQaSignedAt(now);
        order.setStatus(MroDict.REL_RELEASED);
        // 已放行仍占用 active 位：同机不能再起第二条，且这是出位唯一认可的有效记录
        order.setActiveAircraftId(order.getAircraftId());
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, "QA_SIGN", signer,
                "质量终审通过，出厂放行生效（证据快照 v" + snap.getVersionNo() + "），允许出位",
                snap.getVersionNo(), now);
        return new Views.ReleaseActionResult(order.getId(), order.getReleaseNo(), order.getStatus(),
                MroDict.releaseStatusName(order.getStatus()), false, "质量终审通过，飞机已放行，可以安排出位");
    }

    // ============================== 驳回（留原因 → 可修订） ==============================

    @Transactional
    public Views.ReleaseActionResult reject(Long id, ReleaseDtos.RejectReq req) {
        ReleaseOrder order = lockOrder(id);
        String signer = trim(req.getSigner());
        String reason = trim(req.getReason());
        if (signer.isEmpty()) {
            throw new BizException("签字人姓名不能为空");
        }
        if (reason.isEmpty()) {
            throw new BizException("驳回必须填写原因");
        }
        String stage;
        if (MroDict.REL_WAIT_MECH.equals(order.getStatus())) {
            if (signer.equals(order.getInitiator())) {
                throw new BizException("机械放行人不能与发起人为同一人（" + signer + "）");
            }
            stage = "机械放行环节驳回";
        } else if (MroDict.REL_WAIT_QA.equals(order.getStatus())) {
            if (signer.equals(order.getInitiator())) {
                throw new BizException("质量终审人不能与发起人为同一人（" + signer + "）");
            }
            if (signer.equals(order.getMechSigner())) {
                throw new BizException("质量终审人不能与机械放行人为同一人（" + signer + "）");
            }
            stage = "质量终审环节驳回";
        } else {
            throw new BizException("驳回被拒：审签当前为「" + MroDict.releaseStatusName(order.getStatus())
                    + "」，待审状态下才能驳回");
        }
        String drift = detectDrift(order);
        if (drift != null) {
            invalidate(order, drift, INVALID_BY_SYSTEM);
            throw new BizException("驳回未执行：" + drift + "，审签已直接标记为已失效");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(MroDict.REL_REJECTED);
        // 驳回后回到可修订状态：不释放 active 位（仍是该机唯一有效审签，修订后重新提交）
        order.setActiveAircraftId(order.getAircraftId());
        order.setRejectReason(reason);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        event(order, "REJECT", signer, stage + "，原因：" + reason
                + "。发起人修订后再次提交将生成新版本证据快照", currentVersion(id), now);
        return new Views.ReleaseActionResult(order.getId(), order.getReleaseNo(), order.getStatus(),
                MroDict.releaseStatusName(order.getStatus()), false, "已驳回并退回草稿修订，原因已记录");
    }

    // ============================== 上游事实变化 → 立即失效 ==============================

    /**
     * 工卡 / 故障 / 适航状态 / 机位占用发生变化时，由对应模块在【同一事务】内调用。
     * 只处理待机械放行、待质量终审两环的审签；草稿/已驳回允许修订，不失效。
     *
     * @return 被失效的审单数（0 表示没有待审记录受影响）
     */
    @Transactional
    public int invalidateOnFactChange(Long aircraftId, String reason) {
        if (aircraftId == null) {
            return 0;
        }
        List<ReleaseOrder> active = orderRepository.findAllByActiveAircraftId(aircraftId);
        int affected = 0;
        for (ReleaseOrder order : active) {
            if (!MroDict.RELEASE_PENDING_STATUSES.contains(order.getStatus())) {
                continue;
            }
            // 再取锁，避免与终审/机械放行并发；锁内重新读状态
            ReleaseOrder locked = orderRepository.findByIdForUpdate(order.getId()).orElse(order);
            if (!MroDict.RELEASE_PENDING_STATUSES.contains(locked.getStatus())) {
                continue;
            }
            invalidate(locked, reason, INVALID_BY_SYSTEM);
            affected++;
        }
        return affected;
    }

    private void invalidate(ReleaseOrder order, String reason, String operator) {
        Long orderId = order.getId();
        // 外层事务接下来会抛 BizException 整体回滚（终审/机械放行/出位门禁都是这个口径），
        // 所以失效动作必须等外层事务结束后在【全新事务】里提交，既不与外层行锁死锁，也不会被回滚。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            self.invalidateCommitted(orderId, reason, operator);
                        }
                    });
        } else {
            self.invalidateCommitted(orderId, reason, operator);
        }
        // 同步内存对象，外层若还要读它（回滚前），看到的也是 INVALID
        order.setStatus(MroDict.REL_INVALID);
        order.setActiveAircraftId(null);
        order.setInvalidReason(reason);
        order.setInvalidatedAt(LocalDateTime.now());
        order.setUpdatedAt(LocalDateTime.now());
    }

    /** 独立事务提交一次失效（供 {@link #invalidate} 经代理调用） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invalidateCommitted(Long orderId, String reason, String operator) {
        ReleaseOrder order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BizException("放行审签单不存在：id=" + orderId));
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(MroDict.REL_INVALID);
        order.setActiveAircraftId(null);
        order.setInvalidReason(reason);
        order.setInvalidatedAt(now);
        order.setUpdatedAt(now);
        orderRepository.save(order);
        String signers = "（已有签字：机械放行 "
                + (order.getMechSigner() == null ? "未签" : order.getMechSigner())
                + "，质量终审 " + (order.getQaSigner() == null ? "未签" : order.getQaSigner())
                + "，签字保留作审计但不得沿用）";
        event(order, "INVALIDATE", operator, reason + signers, currentVersion(order.getId()), now);
    }

    // ============================== 出位门禁（机库机位模块调用） ==============================

    /**
     * 出位前的唯一放行校验。返回有效放行单；不满足时抛出与页面完全一致的阻断提示。
     *
     * @param bay 要执行出位的机位
     */
    @Transactional
    public ReleaseOrder checkDepartureGate(Bay bay, Aircraft aircraft) {
        if (aircraft == null) {
            throw new BizException("机位 " + (bay == null ? "" : bay.getBayNo()) + " 上没有飞机，无需出位");
        }
        List<ReleaseOrder> active = orderRepository.findAllByActiveAircraftId(aircraft.getId());
        ReleaseOrder valid = null;
        for (ReleaseOrder order : active) {
            if (MroDict.REL_RELEASED.equals(order.getStatus()) && order.getDepartedAt() == null) {
                valid = order;
                break;
            }
        }
        if (valid == null) {
            // 给出"为什么没有效记录"的一致口径：失效 / 驳回 / 草稿 / 根本没发起
            List<ReleaseOrder> mine = orderRepository.findAllByAircraftIdOrderByCreatedAtDesc(aircraft.getId());
            ReleaseOrder newest = mine.isEmpty() ? null : mine.get(0);
            if (newest == null) {
                throw new BizException("飞机 " + aircraft.getTailNo()
                        + " 没有出厂放行记录：请先在「出厂放行审签」完成三人审签并终审通过后再出位");
            }
            String why = switch (newest.getStatus()) {
                case MroDict.REL_INVALID -> "最近的放行审签 " + newest.getReleaseNo()
                        + " 已失效（" + newest.getInvalidReason() + "），必须重新发起";
                case MroDict.REL_REJECTED -> "最近的放行审签 " + newest.getReleaseNo()
                        + " 已驳回（" + newest.getRejectReason() + "），修订重新提交并终审通过后才能出位";
                case MroDict.REL_DRAFT -> "放行审签 " + newest.getReleaseNo()
                        + " 还在草稿/修订中，尚未走完审签";
                case MroDict.REL_WAIT_MECH -> "放行审签 " + newest.getReleaseNo() + " 待机械放行，尚未终审通过";
                case MroDict.REL_WAIT_QA -> "放行审签 " + newest.getReleaseNo() + " 待质量终审，尚未终审通过";
                case MroDict.REL_RELEASED -> "放行已用于出位或被消费，没有可重复使用的放行记录";
                default -> "没有有效已放行记录";
            };
            throw new BizException("飞机 " + aircraft.getTailNo() + " 暂不能从机位 "
                    + (bay == null ? "" : bay.getBayNo()) + " 出位：" + why);
        }
        // 放行对应的是不是【当前这一轮维修事实】：锁内把最新快照与当前事实对一遍
        ReleaseOrder locked = orderRepository.findByIdForUpdate(valid.getId()).orElse(valid);
        if (!MroDict.REL_RELEASED.equals(locked.getStatus()) || locked.getDepartedAt() != null) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 的放行记录状态已变化，请刷新后重试");
        }
        String drift = detectDrift(locked);
        if (drift != null) {
            // 终审通过后上游又变：放行不再对应当前维修事实 → 失效，阻断出位
            invalidate(locked, drift + "（终审通过后事实变化，放行不再对应当前维修事实）", INVALID_BY_SYSTEM);
            throw new BizException("飞机 " + aircraft.getTailNo() + " 暂不能出位：放行 " + locked.getReleaseNo()
                    + " 对应的不是当前这一轮维修事实，已自动失效，请重新发起审签");
        }
        if (!"AIRWORTHY".equals(aircraft.getAirworthy())) {
            throw new BizException("飞机 " + aircraft.getTailNo() + " 当前为「"
                    + MroDict.airworthyName(aircraft.getAirworthy()) + "」，不适航，禁止出位");
        }
        return locked;
    }

    /** 出位成功后消费放行（仍在机位服务的事务里调用） */
    @Transactional
    public void markDeparted(ReleaseOrder order, String bayNo) {
        ReleaseOrder locked = orderRepository.findByIdForUpdate(order.getId()).orElseThrow(
                () -> new BizException("放行记录不存在：id=" + order.getId()));
        if (locked.getDepartedAt() != null) {
            // 并发两个出位请求：只有一个能消费
            throw new BizException("放行 " + locked.getReleaseNo() + " 已用于出位（机位 "
                    + locked.getDepartedBayNo() + "），不能重复出位");
        }
        LocalDateTime now = LocalDateTime.now();
        locked.setDepartedAt(now);
        locked.setDepartedBayNo(bayNo);
        // 出位后释放 active 占位：同机不会再有第二条有效审签，下一轮入位维修可重新发起
        locked.setActiveAircraftId(null);
        locked.setUpdatedAt(now);
        orderRepository.save(locked);
        event(locked, "DEPART", INVALID_BY_SYSTEM,
                "飞机凭终审放行从机位 " + bayNo + " 执行出位，本放行记录消费完毕", currentVersion(locked.getId()), now);
    }

    // ============================== 快照构建 / 事实漂移检测 ==============================

    /**
     * 生成一版不可变证据快照，固化当时的适航状态、机位、全部工卡与未关闭故障，
     * 每条明细都保存当时编号、状态与更新时间。
     */
    private ReleaseSnapshot buildSnapshot(ReleaseOrder order, int version, String kind,
                                          Map<Long, String> inheritBasis, LocalDateTime now) {
        ReleaseFactsCollector.ReleaseFacts facts = factsCollector.collect(order.getAircraftId(), true);
        Aircraft aircraft = facts.aircraft;
        Bay bay = facts.bay;

        ReleaseSnapshot snap = new ReleaseSnapshot();
        snap.setReleaseId(order.getId());
        snap.setVersionNo(version);
        snap.setSnapshotKind(kind);
        snap.setTailNo(aircraft.getTailNo());
        snap.setModel(aircraft.getModel());
        snap.setAirworthy(aircraft.getAirworthy());
        snap.setBayId(bay == null ? null : bay.getId());
        snap.setBayNo(bay == null ? null : bay.getBayNo());
        snap.setBayStatus(bay == null ? null : bay.getStatus());
        snap.setOpenCardCount(facts.openCards.size());
        snap.setOpenDefectCount(facts.openDefects.size());
        snap.setOverdueDefectCount(facts.overdueDefects.size());
        snap.setCreatedAt(now);
        snap = snapshotRepository.save(snap);

        for (WorkCard card : facts.cards) {
            ReleaseSnapshotCard item = new ReleaseSnapshotCard();
            item.setSnapshotId(snap.getId());
            item.setCardId(card.getId());
            item.setCardNo(card.getCardNo());
            item.setStatusCode(card.getStatus());
            item.setTitle(card.getTitle());
            item.setExecutor(card.getExecutor());
            item.setInspector(card.getInspector());
            item.setClosedFlag(MroDict.cardClosed(card.getStatus()) ? 1 : 0);
            item.setSourceUpdatedAt(card.getUpdatedAt());
            cardItemRepository.save(item);
        }
        for (Defect defect : facts.openDefects) {
            saveDefectItem(snap, defect, false, inheritBasis.getOrDefault(defect.getId(), null));
        }
        for (Defect defect : facts.overdueDefects) {
            saveDefectItem(snap, defect, true, inheritBasis.getOrDefault(defect.getId(), null));
        }
        return snap;
    }

    private void saveDefectItem(ReleaseSnapshot snap, Defect defect, boolean overdue, String basis) {
        ReleaseSnapshotDefect item = new ReleaseSnapshotDefect();
        item.setSnapshotId(snap.getId());
        item.setDefectId(defect.getId());
        item.setDefectNo(defect.getDefectNo());
        item.setStatusCode(overdue ? "OVERDUE" : defect.getStatus());
        item.setAtaChapter(defect.getAtaChapter());
        item.setDescription(defect.getDescription());
        item.setDueDate(defect.getDueDate());
        item.setOverdueFlag(overdue ? 1 : 0);
        item.setRetainBasis(basis == null || basis.isBlank() ? null : basis);
        item.setQaConfirmedFlag(0);
        item.setSourceUpdatedAt(defect.getUpdatedAt());
        defectItemRepository.save(item);
    }

    /** 沿用上一版快照里同一条故障（id）的保留依据，已闭环的不再沿用 */
    private Map<Long, String> inheritRetainBasis(Long releaseId) {
        Map<Long, String> map = new HashMap<>();
        snapshotRepository.findFirstByReleaseIdOrderByVersionNoDesc(releaseId).ifPresent(snap -> {
            for (ReleaseSnapshotDefect d : defectItemRepository.findAllBySnapshotIdOrderByDefectNoAsc(snap.getId())) {
                if (d.getRetainBasis() != null && !d.getRetainBasis().isBlank()) {
                    map.put(d.getDefectId(), d.getRetainBasis());
                }
            }
        });
        return map;
    }

    /**
     * 事实漂移检测：把最新版本快照与【当前事实】逐项对比。
     * 覆盖：关联工卡（新增/消失/状态/更新时间）、故障（关闭/新增开放/超期/状态）、
     * 适航状态、机位占用。任一不符返回人话原因；完全一致返回 null。
     */
    private String detectDrift(ReleaseOrder order) {
        ReleaseSnapshot snap = latestSnapshot(order.getId());
        ReleaseFactsCollector.ReleaseFacts facts = factsCollector.collect(order.getAircraftId(), true);

        // 适航状态
        if (!snap.getAirworthy().equals(facts.aircraft.getAirworthy())) {
            return "飞机适航状态已由「" + MroDict.airworthyName(snap.getAirworthy())
                    + "」变为「" + MroDict.airworthyName(facts.aircraft.getAirworthy()) + "」";
        }
        // 机位占用：快照里的机位 / 当前占用机位必须还是同一个且仍由该机占用
        Bay bay = facts.bay;
        Long currentBayId = bay == null ? null : bay.getId();
        boolean stillOccupied = bay != null && "OCCUPIED".equals(bay.getStatus())
                && order.getAircraftId().equals(bay.getCurrentAircraftId());
        if (!java.util.Objects.equals(snap.getBayId(), currentBayId) || !stillOccupied) {
            return "机位占用已变化（快照时为 " + (snap.getBayNo() == null ? "场外" : snap.getBayNo())
                    + "，当前为 " + (bay == null ? "场外" : bay.getBayNo() + "/" + bay.getStatus()) + "）";
        }

        // 工卡
        List<ReleaseSnapshotCard> snapCards =
                cardItemRepository.findAllBySnapshotIdOrderByCardNoAsc(snap.getId());
        Map<Long, WorkCard> currentCardMap = new HashMap<>();
        for (WorkCard c : facts.cards) {
            currentCardMap.put(c.getId(), c);
        }
        Set<Long> snapCardIds = new HashSet<>();
        for (ReleaseSnapshotCard sc : snapCards) {
            snapCardIds.add(sc.getCardId());
            WorkCard current = currentCardMap.get(sc.getCardId());
            if (current == null) {
                return "关联工卡 " + sc.getCardNo() + " 已不存在";
            }
            if (!sc.getStatusCode().equals(current.getStatus())) {
                return "关联工卡 " + sc.getCardNo() + " 状态已由「"
                        + MroDict.cardStatusName(sc.getStatusCode()) + "」变为「"
                        + MroDict.cardStatusName(current.getStatus()) + "」";
            }
            if (!java.util.Objects.equals(sc.getSourceUpdatedAt(), current.getUpdatedAt())) {
                return "关联工卡 " + sc.getCardNo() + " 在快照之后被更新（执行人/检验/工时等发生变化）";
            }
        }
        for (WorkCard c : facts.cards) {
            if (!snapCardIds.contains(c.getId())) {
                return "快照之后新增了关联工卡 " + c.getCardNo() + "（当前"
                        + MroDict.cardStatusName(c.getStatus()) + "）";
            }
        }

        // 故障：快照只存未关闭的
        List<ReleaseSnapshotDefect> snapDefects =
                defectItemRepository.findAllBySnapshotIdOrderByDefectNoAsc(snap.getId());
        Map<Long, Defect> currentDefectMap = new HashMap<>();
        for (Defect d : facts.defects) {
            currentDefectMap.put(d.getId(), d);
        }
        Set<Long> snapDefectIds = new HashSet<>();
        for (ReleaseSnapshotDefect sd : snapDefects) {
            snapDefectIds.add(sd.getDefectId());
            Defect current = currentDefectMap.get(sd.getDefectId());
            if (current == null || "CLOSED".equals(current.getStatus())) {
                return "保留故障 " + sd.getDefectNo() + " 已在快照之后闭环，维修事实发生变化";
            }
            boolean nowOverdue = "OVERDUE".equals(current.getStatus())
                    || current.getDueDate().isBefore(facts.today);
            boolean wasOverdue = sd.getOverdueFlag() == 1;
            if (nowOverdue != wasOverdue) {
                return "故障 " + sd.getDefectNo() + (nowOverdue ? " 已超过保留到期日，变为超期故障"
                        : " 超期状态已解除");
            }
            if (!java.util.Objects.equals(sd.getSourceUpdatedAt(), current.getUpdatedAt())) {
                return "故障 " + sd.getDefectNo() + " 在快照之后被更新";
            }
        }
        for (Defect d : facts.defects) {
            if ("CLOSED".equals(d.getStatus())) {
                continue;
            }
            if (!snapDefectIds.contains(d.getId())) {
                boolean overdue = "OVERDUE".equals(d.getStatus()) || d.getDueDate().isBefore(facts.today);
                return "快照之后新增了" + (overdue ? "超期" : "开放") + "故障 " + d.getDefectNo();
            }
        }
        return null;
    }

    private List<String> gateBlockers(ReleaseFactsCollector.ReleaseFacts facts) {
        List<String> blockers = new ArrayList<>();
        if (!"AIRWORTHY".equals(facts.aircraft.getAirworthy())) {
            blockers.add("飞机当前为「" + MroDict.airworthyName(facts.aircraft.getAirworthy()) + "」，不是适航状态");
        }
        boolean occupied = facts.bay != null && "OCCUPIED".equals(facts.bay.getStatus())
                && facts.aircraft.getId().equals(facts.bay.getCurrentAircraftId());
        if (!occupied) {
            blockers.add("飞机已不在占用中的机位上");
        }
        if (!facts.openCards.isEmpty()) {
            blockers.add("仍有未闭环工卡 " + facts.openCards.size() + " 张");
        }
        if (!facts.overdueDefects.isEmpty()) {
            blockers.add("存在超期故障 " + facts.overdueDefects.size() + " 条");
        }
        return blockers;
    }

    // ============================== 视图组装 ==============================

    private Views.SnapshotView toSnapshotView(ReleaseSnapshot snap, int latestVersion,
                                              Map<Long, WorkCard> currentCardMap,
                                              Map<Long, Defect> currentDefectMap,
                                              java.time.LocalDate today) {
        List<Views.SnapshotCardItem> cardItems = new ArrayList<>();
        for (ReleaseSnapshotCard sc : cardItemRepository.findAllBySnapshotIdOrderByCardNoAsc(snap.getId())) {
            WorkCard current = currentCardMap.get(sc.getCardId());
            String changeKind = null;
            boolean changed = false;
            if (current == null) {
                changed = true;
                changeKind = "GONE";
            } else if (!sc.getStatusCode().equals(current.getStatus())) {
                changed = true;
                changeKind = "STATUS";
            } else if (!java.util.Objects.equals(sc.getSourceUpdatedAt(), current.getUpdatedAt())) {
                changed = true;
                changeKind = "UPDATED";
            }
            cardItems.add(new Views.SnapshotCardItem(
                    sc.getId(), sc.getCardId(), sc.getCardNo(), sc.getStatusCode(),
                    MroDict.cardStatusName(sc.getStatusCode()), sc.getTitle(), sc.getExecutor(),
                    sc.getInspector(), sc.getClosedFlag() == 1, sc.getSourceUpdatedAt(),
                    current == null ? null : current.getStatus(),
                    current == null ? "已不存在" : MroDict.cardStatusName(current.getStatus()),
                    changed, changeKind));
        }
        // 快照之后新增的工卡
        Set<Long> snapIds = new HashSet<>();
        for (Views.SnapshotCardItem item : cardItems) {
            snapIds.add(item.cardId());
        }
        for (WorkCard c : currentCardMap.values()) {
            if (!snapIds.contains(c.getId())) {
                cardItems.add(new Views.SnapshotCardItem(
                        null, c.getId(), c.getCardNo(), c.getStatus(),
                        MroDict.cardStatusName(c.getStatus()), c.getTitle(), c.getExecutor(),
                        c.getInspector(), MroDict.cardClosed(c.getStatus()), c.getUpdatedAt(),
                        c.getStatus(), MroDict.cardStatusName(c.getStatus()), true, "NEW"));
            }
        }
        cardItems.sort(Comparator.comparing(Views.SnapshotCardItem::cardNo));

        List<Views.SnapshotDefectItem> defectItems = new ArrayList<>();
        for (ReleaseSnapshotDefect sd : defectItemRepository.findAllBySnapshotIdOrderByDefectNoAsc(snap.getId())) {
            Defect current = currentDefectMap.get(sd.getDefectId());
            String changeKind = null;
            boolean changed = false;
            String currentStatus = current == null ? null : current.getStatus();
            String currentStatusName = current == null ? "已不存在" : MroDict.defectStatusName(current.getStatus());
            if (current == null || "CLOSED".equals(currentStatus)) {
                changed = true;
                changeKind = "CLOSED";
                currentStatus = current == null ? null : "CLOSED";
            } else {
                boolean nowOverdue = "OVERDUE".equals(currentStatus) || current.getDueDate().isBefore(today);
                if (nowOverdue != (sd.getOverdueFlag() == 1)) {
                    changed = true;
                    changeKind = nowOverdue ? "BECAME_OVERDUE" : "OVERDUE_CLEARED";
                } else if (!java.util.Objects.equals(sd.getSourceUpdatedAt(), current.getUpdatedAt())) {
                    changed = true;
                    changeKind = "UPDATED";
                }
            }
            defectItems.add(new Views.SnapshotDefectItem(
                    sd.getId(), sd.getDefectId(), sd.getDefectNo(), sd.getStatusCode(),
                    MroDict.defectStatusName(sd.getStatusCode()), sd.getAtaChapter(), sd.getDescription(),
                    sd.getDueDate(), sd.getOverdueFlag() == 1, sd.getRetainBasis(),
                    sd.getOverdueFlag() == 0 && (sd.getRetainBasis() == null || sd.getRetainBasis().isBlank()),
                    sd.getQaConfirmedFlag() == 1, sd.getSourceUpdatedAt(),
                    currentStatus, currentStatusName, changed, changeKind));
        }
        Set<Long> snapDefIds = new HashSet<>();
        for (Views.SnapshotDefectItem item : defectItems) {
            snapDefIds.add(item.defectId());
        }
        for (Defect d : currentDefectMap.values()) {
            if ("CLOSED".equals(d.getStatus()) || snapDefIds.contains(d.getId())) {
                continue;
            }
            boolean overdue = "OVERDUE".equals(d.getStatus()) || d.getDueDate().isBefore(today);
            defectItems.add(new Views.SnapshotDefectItem(
                    null, d.getId(), d.getDefectNo(), d.getStatus(),
                    MroDict.defectStatusName(d.getStatus()), d.getAtaChapter(), d.getDescription(),
                    d.getDueDate(), overdue, null, !overdue, false, d.getUpdatedAt(),
                    d.getStatus(), MroDict.defectStatusName(d.getStatus()), true, "NEW"));
        }
        defectItems.sort(Comparator.comparing(Views.SnapshotDefectItem::defectNo));

        return new Views.SnapshotView(
                snap.getId(), snap.getVersionNo(), snap.getSnapshotKind(),
                "DRAFT_SNAPSHOT".equals(snap.getSnapshotKind()) ? "发起快照" : "提交快照",
                snap.getTailNo(), snap.getModel(), snap.getAirworthy(),
                MroDict.airworthyName(snap.getAirworthy()),
                snap.getBayId(), snap.getBayNo(), snap.getBayStatus(),
                snap.getBayStatus() == null ? "" : MroDict.bayStatusName(snap.getBayStatus()),
                snap.getOpenCardCount(), snap.getOpenDefectCount(), snap.getOverdueDefectCount(),
                snap.getCreatedAt(), snap.getVersionNo() == latestVersion, cardItems, defectItems);
    }

    // ============================== 内部小工具 ==============================

    private ReleaseOrder requireOrder(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new BizException("放行审签单不存在：id=" + id));
    }

    private ReleaseOrder lockOrder(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BizException("放行审签单不存在：id=" + id));
    }

    private ReleaseSnapshot latestSnapshot(Long releaseId) {
        return snapshotRepository.findFirstByReleaseIdOrderByVersionNoDesc(releaseId)
                .orElseThrow(() -> new BizException("放行审签缺少证据快照数据：id=" + releaseId));
    }

    private int currentVersion(Long releaseId) {
        return snapshotRepository.findFirstByReleaseIdOrderByVersionNoDesc(releaseId)
                .map(ReleaseSnapshot::getVersionNo).orElse(0);
    }

    private void requireEditable(ReleaseOrder order) {
        if (!MroDict.REL_DRAFT.equals(order.getStatus()) && !MroDict.REL_REJECTED.equals(order.getStatus())) {
            throw new BizException("审签当前为「" + MroDict.releaseStatusName(order.getStatus())
                    + "」，只有草稿/已驳回状态可以修订");
        }
    }

    private void event(ReleaseOrder order, String type, String operator, String detail,
                       Integer snapshotVersion, LocalDateTime at) {
        ReleaseEvent event = new ReleaseEvent();
        event.setReleaseId(order.getId());
        event.setEventType(type);
        event.setOperator(operator);
        event.setDetail(detail);
        event.setSnapshotVersion(snapshotVersion);
        event.setEventAt(at);
        eventRepository.save(event);
    }

    private Views.ReleaseActionResult idempotentResult(ReleaseOrder order, String message) {
        return new Views.ReleaseActionResult(order.getId(), order.getReleaseNo(), order.getStatus(),
                MroDict.releaseStatusName(order.getStatus()), true, message);
    }

    private String nextReleaseNo() {
        long count = orderRepository.count();
        return "RO-" + java.time.Year.now().getValue() + "-" + String.format("%04d", count + 1);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String eventName(String type) {
        return switch (type) {
            case "CREATE" -> "发起审签";
            case "SUBMIT" -> "提交审签";
            case "SUBMIT_BLOCKED" -> "提交被拦";
            case "RETAIN" -> "修订保留依据";
            case "MECH_SIGN" -> "机械放行";
            case "QA_CONFIRM" -> "确认保留故障";
            case "QA_SIGN" -> "质量终审通过";
            case "REJECT" -> "驳回";
            case "INVALIDATE" -> "标记失效";
            case "DEPART" -> "执行出位";
            default -> type;
        };
    }
}
