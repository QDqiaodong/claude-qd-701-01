package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.AdCompliance;
import com.aircraft.mro.domain.AdComplianceLink;
import com.aircraft.mro.domain.AdDirective;
import com.aircraft.mro.domain.AdEvidence;
import com.aircraft.mro.domain.AdEvent;
import com.aircraft.mro.domain.AdRecalcJob;
import com.aircraft.mro.domain.AdRecalcJobItem;
import com.aircraft.mro.domain.AdRevision;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.dto.AdDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.AdComplianceLinkRepository;
import com.aircraft.mro.repository.AdComplianceRepository;
import com.aircraft.mro.repository.AdDirectiveRepository;
import com.aircraft.mro.repository.AdEvidenceRepository;
import com.aircraft.mro.repository.AdEventRepository;
import com.aircraft.mro.repository.AdRecalcJobItemRepository;
import com.aircraft.mro.repository.AdRecalcJobRepository;
import com.aircraft.mro.repository.AdRevisionRepository;
import com.aircraft.mro.repository.WorkCardRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 模块七：适航指令落实追踪。
 *
 * <p>所有"是否适用 / 何时到期 / 是否已落实"的判断都在服务端：
 * <ul>
 *   <li>{@link AdEvaluation} 是状态口径唯一来源；</li>
 *   <li>每次计算都在行级悲观锁内重新读取飞机累计值、工卡与证据的最新状态，
 *       旧 tick 的结果不会覆盖新 tick（飞机登记飞行、工卡变化与批量重算并发时按最新数据收敛）；</li>
 *   <li>修订版生效后不可变，范围/阈值变化另出新版本，旧版结果与证据全部保留；</li>
 *   <li>批量重算为后台异步作业，逐机独立短事务，进度持久化，部分失败可续跑且不重复生成结果。</li>
 * </ul>
 */
@Service
public class AdService {

    private static final Logger log = LoggerFactory.getLogger(AdService.class);

    private final AdDirectiveRepository directiveRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdComplianceLinkRepository linkRepository;
    private final AdEvidenceRepository evidenceRepository;
    private final AdEventRepository eventRepository;
    private final AdRecalcJobRepository jobRepository;
    private final AdRecalcJobItemRepository itemRepository;
    private final AircraftRepository aircraftRepository;
    private final WorkCardRepository workCardRepository;
    private final AdService self;

    /** 后台批量重算单线程：同一时刻只有一个批次在跑，逐机短事务，不占住普通查询与飞行登记。 */
    private final ExecutorService batchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ad-recalc-worker");
        t.setDaemon(true);
        return t;
    });

    /** 只有一个 worker 线程会调度批次，用它保证同作业不会被重复调度。 */
    private final ReentrantLock scheduleLock = new ReentrantLock();

    public AdService(AdDirectiveRepository directiveRepository,
                     AdRevisionRepository revisionRepository,
                     AdComplianceRepository complianceRepository,
                     AdComplianceLinkRepository linkRepository,
                     AdEvidenceRepository evidenceRepository,
                     AdEventRepository eventRepository,
                     AdRecalcJobRepository jobRepository,
                     AdRecalcJobItemRepository itemRepository,
                     AircraftRepository aircraftRepository,
                     WorkCardRepository workCardRepository,
                     @org.springframework.context.annotation.Lazy AdService self) {
        this.directiveRepository = directiveRepository;
        this.revisionRepository = revisionRepository;
        this.complianceRepository = complianceRepository;
        this.linkRepository = linkRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.aircraftRepository = aircraftRepository;
        this.workCardRepository = workCardRepository;
        this.self = self;
    }

    // ============================== 读：总览 / 目录 / 版本 / 清单 ==============================

    @Transactional(readOnly = true)
    public Views.AdOverview overview() {
        long overdue = 0;
        long near = 0;
        long pending = 0;
        long complied = 0;
        // 只统计当前生效版的清单，旧版历史不计入待办
        for (AdRevision rev : revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE")) {
            for (AdCompliance c : complianceRepository.findAllByRevisionId(rev.getId())) {
                switch (c.getStatus()) {
                    case MroDict.AD_OVERDUE -> overdue++;
                    case MroDict.AD_NEAR_DUE -> near++;
                    case MroDict.AD_PENDING -> pending++;
                    case MroDict.AD_COMPLIED -> complied++;
                    default -> {
                    }
                }
            }
        }
        long running = jobRepository.findAllByStatus("RUNNING").size();
        return new Views.AdOverview(directiveRepository.count(), overdue, near, pending, complied, running);
    }

    @Transactional(readOnly = true)
    public List<Views.AdDirectiveView> directives() {
        Map<Long, List<AdRevision>> revMap = new HashMap<>();
        for (AdRevision r : revisionRepository.findAllByOrderByIdAsc()) {
            revMap.computeIfAbsent(r.getDirectiveId(), k -> new ArrayList<>()).add(r);
        }
        List<Views.AdDirectiveView> list = new ArrayList<>();
        for (AdDirective d : directiveRepository.findAllByOrderByAdNoAsc()) {
            List<AdRevision> revs = revMap.getOrDefault(d.getId(), List.of());
            AdRevision current = currentRevision(revs);
            list.add(new Views.AdDirectiveView(
                    d.getId(), d.getAdNo(), d.getAuthority(), d.getTitle(), d.getLatestRevision(),
                    current == null ? null : current.getId(),
                    current == null ? null : current.getStatus(),
                    current == null ? null : MroDict.adRevisionStatusName(current.getStatus()),
                    current == null ? null : current.getApplicableModels(),
                    current == null ? null : current.getTailRangeFrom(),
                    current == null ? null : current.getTailRangeTo(),
                    current == null ? null : current.getEffectiveDate(),
                    current == null ? null : current.getDueDate(),
                    current == null ? null : current.getDueFh(),
                    current == null ? null : current.getDueCycles(),
                    current == null ? null : nz(current.getWarnDays()),
                    current == null ? null : nzbd(current.getWarnFh()),
                    current == null ? null : nz(current.getWarnCycles()),
                    revs.size(),
                    current == null ? null : current.getPublishedAt()));
        }
        return list;
    }

    @Transactional(readOnly = true)
    public List<Views.AdRevisionView> revisions(Long directiveId) {
        AdDirective d = requireDirective(directiveId);
        List<Views.AdRevisionView> list = new ArrayList<>();
        for (AdRevision r : revisionRepository.findAllByDirectiveIdOrderByRevisionNoAsc(directiveId)) {
            list.add(toRevisionView(d.getAdNo(), r));
        }
        return list;
    }

    /**
     * 落实清单查询。可按修订版/机型/机号/状态筛选；状态与余量全部读服务端持久化结果，
     * 前端不做任何期限计算。
     */
    @Transactional(readOnly = true)
    public List<Views.AdComplianceView> complianceList(Long revisionId, Long directiveId, String model,
                                                       String tailKeyword, String status,
                                                       boolean includeSuperseded) {
        Map<Long, Aircraft> aircraftMap = aircraftMap();
        Map<Long, AdRevision> revisionMap = new HashMap<>();
        for (AdRevision r : revisionRepository.findAllByOrderByIdAsc()) {
            revisionMap.put(r.getId(), r);
        }
        Map<Long, AdDirective> directiveMap = new HashMap<>();
        for (AdDirective d : directiveRepository.findAll()) {
            directiveMap.put(d.getId(), d);
        }

        List<AdCompliance> rows;
        if (revisionId != null) {
            rows = complianceRepository.findAllByRevisionId(revisionId);
        } else if (directiveId != null) {
            rows = new ArrayList<>();
            for (AdRevision r : revisionRepository.findAllByDirectiveIdOrderByRevisionNoAsc(directiveId)) {
                if (!includeSuperseded && "SUPERSEDED".equals(r.getStatus())) {
                    continue;
                }
                rows.addAll(complianceRepository.findAllByRevisionId(r.getId()));
            }
        } else {
            rows = new ArrayList<>();
            for (AdRevision r : revisionRepository.findAllByOrderByIdAsc()) {
                if (!includeSuperseded && "SUPERSEDED".equals(r.getStatus())) {
                    continue;
                }
                rows.addAll(complianceRepository.findAllByRevisionId(r.getId()));
            }
        }

        List<Views.AdComplianceView> list = new ArrayList<>();
        for (AdCompliance c : rows) {
            AdRevision rev = revisionMap.get(c.getRevisionId());
            if (rev == null) {
                continue;
            }
            Aircraft a = aircraftMap.get(c.getAircraftId());
            if (a == null) {
                continue;
            }
            if (model != null && !model.isBlank() && !model.equalsIgnoreCase(a.getModel())) {
                continue;
            }
            if (tailKeyword != null && !tailKeyword.isBlank()
                    && (a.getTailNo() == null || !a.getTailNo().contains(tailKeyword.trim()))) {
                continue;
            }
            if (status != null && !status.isBlank() && !status.equals(c.getStatus())) {
                continue;
            }
            AdDirective d = directiveMap.get(rev.getDirectiveId());
            list.add(toComplianceView(c, rev, d, a, validCounts(c.getId())));
        }
        list.sort((x, y) -> {
            int c = x.adNo().compareTo(y.adNo());
            if (c != 0) {
                return c;
            }
            c = y.revisionNo().compareTo(x.revisionNo());
            if (c != 0) {
                return c;
            }
            return x.tailNo().compareTo(y.tailNo());
        });
        return list;
    }

    @Transactional(readOnly = true)
    public Views.AdComplianceDetail complianceDetail(Long id) {
        AdCompliance c = complianceRepository.findById(id)
                .orElseThrow(() -> new BizException("落实清单条目不存在：id=" + id));
        AdRevision rev = revisionRepository.findById(c.getRevisionId())
                .orElseThrow(() -> new BizException("修订版数据缺失：id=" + c.getRevisionId()));
        AdDirective d = directiveRepository.findById(rev.getDirectiveId())
                .orElseThrow(() -> new BizException("指令数据缺失：id=" + rev.getDirectiveId()));
        Aircraft a = aircraftRepository.findById(c.getAircraftId())
                .orElseThrow(() -> new BizException("飞机数据缺失：id=" + c.getAircraftId()));
        Views.AdComplianceView head = toComplianceView(c, rev, d, a, validCounts(c.getId()));

        Map<Long, WorkCard> cardMap = new HashMap<>();
        for (WorkCard card : workCardRepository.findAllByOrderByCardNoAsc()) {
            cardMap.put(card.getId(), card);
        }
        Map<Long, Aircraft> aircraftMap = aircraftMap();
        List<Views.AdLinkView> links = new ArrayList<>();
        for (AdComplianceLink link : linkRepository.findAllByComplianceId(c.getId())) {
            WorkCard card = cardMap.get(link.getWorkCardId());
            Aircraft linkedA = aircraftMap.get(link.getLinkedAircraftId());
            Aircraft currentA = card == null ? null : aircraftMap.get(card.getAircraftId());
            links.add(new Views.AdLinkView(
                    link.getId(), link.getWorkCardId(), link.getCardNo(),
                    link.getLinkedAircraftId(), linkedA == null ? "?" : linkedA.getTailNo(),
                    link.getLinkedBy(), link.getLinkedAt(),
                    link.getValidFlag() == 1, link.getInvalidReason(), link.getInvalidatedAt(),
                    card == null ? null : card.getStatus(),
                    card == null ? "已不存在" : MroDict.cardStatusName(card.getStatus()),
                    card == null ? null : card.getAircraftId(),
                    currentA == null ? null : currentA.getTailNo(),
                    card != null && "CLOSED".equals(card.getStatus())));
        }

        List<Views.AdEvidenceView> evidences = new ArrayList<>();
        for (AdEvidence e : evidenceRepository.findAllByComplianceIdOrderByRegisteredAtAscIdAsc(c.getId())) {
            evidences.add(toEvidenceView(e));
        }

        List<Views.AdEventView> events = new ArrayList<>();
        for (AdEvent e : eventRepository.findAllByComplianceIdOrderByEventAtAscIdAsc(c.getId())) {
            events.add(toEventView(e));
        }
        return new Views.AdComplianceDetail(head, links, evidences, events);
    }

    // ============================== 指令 / 修订版维护 ==============================

    @Transactional
    public Views.AdRevisionView createDirective(AdDtos.DirectiveCreateReq req) {
        String adNo = req.getAdNo().trim();
        if (directiveRepository.existsByAdNo(adNo)) {
            throw new BizException("适航指令编号 " + adNo + " 已存在，不能重复建档");
        }
        LocalDateTime now = LocalDateTime.now();
        AdDirective d = new AdDirective();
        d.setAdNo(adNo);
        d.setAuthority(req.getAuthority().trim());
        d.setTitle(req.getTitle().trim());
        d.setLatestRevision("R0");
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        d = directiveRepository.save(d);

        AdRevision rev = applyContent(new AdRevision(), d.getId(), "R0", req.getRevision(), now);
        boolean publish = req.getPublish() == null || req.getPublish();
        rev.setStatus(publish ? "EFFECTIVE" : "DRAFT");
        rev.setPublishedAt(publish ? now : null);
        rev = revisionRepository.save(rev);

        event(d.getId(), rev.getId(), null, null, "REVISION_PUBLISH", "SYSTEM",
                (publish ? "指令建档并发布 R0" : "指令建档，R0 草稿待发布"), null, null, now);
        if (publish) {
            // 发布后异步全量重算（短事务提交后再调度，避免读到未提交数据）
            final AdRevision published = rev;
            scheduleAfterCommit(() -> self.createRevisionJob(published.getId(),
                    "R0 发布，按新适用范围/阈值重算覆盖机队", "SYSTEM", null));
        }
        return toRevisionView(d.getAdNo(), rev);
    }

    @Transactional
    public Views.AdRevisionView updateDirective(Long id, AdDtos.DirectiveUpdateReq req) {
        AdDirective d = requireDirective(id);
        d.setAuthority(req.getAuthority().trim());
        d.setTitle(req.getTitle().trim());
        d.setUpdatedAt(LocalDateTime.now());
        directiveRepository.save(d);
        AdRevision current = currentRevision(revisionRepository.findAllByDirectiveIdOrderByRevisionNoAsc(id));
        return toRevisionView(d.getAdNo(), current);
    }

    /** 编辑草稿内容（生效版不可改） */
    @Transactional
    public Views.AdRevisionView updateDraft(Long revisionId, AdDtos.DraftUpdateReq req) {
        AdRevision rev = requireRevision(revisionId);
        if (!"DRAFT".equals(rev.getStatus())) {
            throw new BizException("修订版 " + rev.getRevisionNo() + " 已"
                    + MroDict.adRevisionStatusName(rev.getStatus()) + "，范围/阈值不可直接修改；请创建新的修订版");
        }
        LocalDateTime now = LocalDateTime.now();
        applyContent(rev, rev.getDirectiveId(), rev.getRevisionNo(), req.getRevision(), now);
        rev = revisionRepository.save(rev);
        return toRevisionView(requireDirective(rev.getDirectiveId()).getAdNo(), rev);
    }

    /** 基于当前生效版创建下一修订版草稿 */
    @Transactional
    public Views.AdRevisionView newRevision(Long directiveId, AdDtos.NewRevisionReq req) {
        AdDirective d = requireDirective(directiveId);
        List<AdRevision> all = revisionRepository.findAllByDirectiveIdForUpdate(directiveId);
        AdRevision base = currentRevision(all);
        if (base == null) {
            throw new BizException("指令 " + d.getAdNo() + " 还没有任何修订版");
        }
        boolean hasDraft = all.stream().anyMatch(r -> "DRAFT".equals(r.getStatus()));
        if (hasDraft) {
            throw new BizException("指令 " + d.getAdNo() + " 已存在草稿修订版，请先发布或删除该草稿");
        }
        String nextNo = nextRevisionNo(all);
        LocalDateTime now = LocalDateTime.now();
        AdRevision rev = new AdRevision();
        rev.setDirectiveId(directiveId);
        rev.setRevisionNo(nextNo);
        rev.setStatus("DRAFT");
        rev.setCreatedAt(now);
        AdDtos.RevisionContent content = req.getRevision() != null ? req.getRevision() : cloneContent(base);
        applyContent(rev, directiveId, nextNo, content, now);
        rev = revisionRepository.save(rev);
        event(directiveId, rev.getId(), null, null, "REVISION_PUBLISH", req.getOperator(),
                "创建修订草稿 " + nextNo + "（基于 " + base.getRevisionNo() + "，尚未发布，不影响现行判断）",
                null, null, now);
        return toRevisionView(d.getAdNo(), rev);
    }

    /** 发布草稿：旧生效版转 SUPERSEDED 冻结，新版生效，按新版重算覆盖机队（历史行保留） */
    @Transactional
    public Views.AdRevisionView publish(Long revisionId, AdDtos.PublishReq req) {
        AdRevision rev = revisionRepository.findByIdForUpdate(revisionId)
                .orElseThrow(() -> new BizException("修订版不存在：id=" + revisionId));
        if (!"DRAFT".equals(rev.getStatus())) {
            throw new BizException("只有草稿状态的修订版可以发布，当前为「"
                    + MroDict.adRevisionStatusName(rev.getStatus()) + "」");
        }
        AdDirective d = requireDirective(rev.getDirectiveId());
        LocalDateTime now = LocalDateTime.now();
        List<AdRevision> siblings = revisionRepository.findAllByDirectiveIdForUpdate(d.getId());
        for (AdRevision r : siblings) {
            if ("EFFECTIVE".equals(r.getStatus())) {
                r.setStatus("SUPERSEDED");
                revisionRepository.save(r);
            }
        }
        rev.setStatus("EFFECTIVE");
        rev.setPublishedAt(now);
        revisionRepository.save(rev);
        d.setLatestRevision(rev.getRevisionNo());
        d.setUpdatedAt(now);
        directiveRepository.save(d);
        event(d.getId(), rev.getId(), null, null, "REVISION_PUBLISH", req.getOperator(),
                rev.getRevisionNo() + " 已生效：按新适用范围/阈值重算覆盖机队；旧版本判断结果与证据全部冻结保留",
                null, null, now);
        scheduleAfterCommit(() -> self.createRevisionJob(rev.getId(),
                rev.getRevisionNo() + " 生效，按新适用范围/阈值重算覆盖机队", req.getOperator(), null));
        return toRevisionView(d.getAdNo(), rev);
    }

    private AdRevision applyContent(AdRevision rev, Long directiveId, String no,
                                    AdDtos.RevisionContent c, LocalDateTime now) {
        if (c.getApplicableModels() == null || c.getApplicableModels().isEmpty()) {
            throw new BizException("适用机型至少选择一个");
        }
        List<String> models = new ArrayList<>();
        for (String m : c.getApplicableModels()) {
            String t = m == null ? "" : m.trim().toUpperCase();
            if (!t.isEmpty()) {
                if (!MroDict.isValidModel(t)) {
                    throw new BizException("未知机型：" + m + "（仅支持 B737 / A320 / A330 / ARJ21）");
                }
                if (!models.contains(t)) {
                    models.add(t);
                }
            }
        }
        rev.setDirectiveId(directiveId);
        rev.setRevisionNo(no);
        rev.setTitle(c.getTitle().trim());
        rev.setEffectiveDate(c.getEffectiveDate());
        rev.setApplicableModels(String.join(";", models));
        rev.setTailRangeFrom(blankToNull(c.getTailRangeFrom()));
        rev.setTailRangeTo(blankToNull(c.getTailRangeTo()));
        rev.setDueDate(c.getDueDate());
        rev.setDueFh(c.getDueFh());
        rev.setDueCycles(c.getDueCycles());
        rev.setWarnDays(c.getWarnDays() == null ? 0 : Math.max(0, c.getWarnDays()));
        rev.setWarnFh(c.getWarnFh() == null ? BigDecimal.ZERO : c.getWarnFh());
        rev.setWarnCycles(c.getWarnCycles() == null ? 0 : Math.max(0, c.getWarnCycles()));
        rev.setRevisionNote(blankToNull(c.getRevisionNote()));
        if (rev.getCreatedAt() == null) {
            rev.setCreatedAt(now);
        }
        return rev;
    }

    private AdDtos.RevisionContent cloneContent(AdRevision base) {
        AdDtos.RevisionContent c = new AdDtos.RevisionContent();
        c.setTitle(base.getTitle());
        c.setEffectiveDate(base.getEffectiveDate());
        c.setApplicableModels(new ArrayList<>(List.of(base.getApplicableModels().split(";"))));
        c.setTailRangeFrom(base.getTailRangeFrom());
        c.setTailRangeTo(base.getTailRangeTo());
        c.setDueDate(base.getDueDate());
        c.setDueFh(base.getDueFh());
        c.setDueCycles(base.getDueCycles());
        c.setWarnDays(base.getWarnDays());
        c.setWarnFh(base.getWarnFh());
        c.setWarnCycles(base.getWarnCycles());
        c.setRevisionNote(base.getRevisionNote());
        return c;
    }

    private String nextRevisionNo(List<AdRevision> all) {
        int max = -1;
        for (AdRevision r : all) {
            String no = r.getRevisionNo();
            if (no != null && no.startsWith("R")) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(1)));
                } catch (NumberFormatException ignore) {
                    // 非标准 Rn 版号忽略
                }
            }
        }
        return "R" + (max + 1);
    }

    // ============================== 核心：单机重算（事务内，锁行重算） ==============================

    /**
     * 飞机登记一次飞行后调用：只重算该机的【生效版】清单，不碰其他飞机。
     * 在飞行登记的同一事务内执行，行锁保证最终状态以最新累计值收敛。
     *
     * @return 被重算的清单条目数
     */
    @Transactional
    public int recalcAircraftOnFlight(Long aircraftId) {
        List<Long> revisionIds = revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE")
                .stream().map(AdRevision::getId).toList();
        int n = 0;
        for (Long revId : revisionIds) {
            recomputeLocked(revId, aircraftId, "FLIGHT", true, "飞机累计飞行数据更新");
            n++;
        }
        return n;
    }

    /** 事务化地确保清单行存在并按最新数据计算一次（测试/同步单架重算入口）。 */
    @Transactional
    public Views.AdComplianceView ensureAndRecompute(Long revisionId, Long aircraftId,
                                                     String trigger, String reason) {
        return recomputeLocked(revisionId, aircraftId, trigger, false, reason);
    }

    /** 工卡状态变化/改挂/取消后调用：只重算把该工卡作为凭证（含失效历史凭证）的清单条目 */
    @Transactional
    public int recalcByWorkCardChange(Long workCardId, String reason) {
        int n = 0;
        for (AdComplianceLink link : linkRepository.findAllByWorkCardId(workCardId)) {
            AdCompliance c = complianceRepository.findById(link.getComplianceId()).orElse(null);
            if (c == null || "NOT_APPLICABLE".equals(c.getStatus())) {
                continue;
            }
            AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElse(null);
            // 旧版本冻结：只复核凭证标志，状态留作历史，不再重算
            if (rev == null || "SUPERSEDED".equals(rev.getStatus())) {
                refreshLinksForFrozen(c.getId(), reason);
                continue;
            }
            recomputeLocked(rev.getId(), c.getAircraftId(), "CARD", true, reason);
            n++;
        }
        return n;
    }

    /**
     * 单条目重算（必须在事务内调用）：
     * <ol>
     *   <li>对清单行（不存在则先建）加悲观锁，同 (修订版, 飞机) 只有一个当前结果；</li>
     *   <li>锁内重读飞机、工卡、证据的最新状态，重新跑引擎，旧 tick 不覆盖新 tick；</li>
     *   <li>先把已取消/重开/改挂的工卡关联标记失效（历史行保留）；</li>
     *   <li>引擎状态为 COMPLIED 候选时，凭证必须仍全部有效且证据齐全，否则降为 PENDING。</li>
     * </ol>
     *
     * @return 重算后的视图（供批量作业计数 / 单条手动重算返回）
     */
    public Views.AdComplianceView recomputeLocked(Long revisionId, Long aircraftId,
                                                  String trigger, boolean emitEvent, String reason) {
        AdRevision rev = revisionRepository.findByIdForUpdate(revisionId)
                .orElseThrow(() -> new BizException("修订版不存在：id=" + revisionId));
        Aircraft aircraft = aircraftRepository.findById(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));

        AdCompliance c = complianceRepository.findByRevisionAircraftForUpdate(revisionId, aircraftId)
                .orElseGet(() -> {
                    AdCompliance fresh = new AdCompliance();
                    fresh.setRevisionId(revisionId);
                    fresh.setAircraftId(aircraftId);
                    fresh.setStatus(MroDict.AD_PENDING);
                    fresh.setApplicable(1);
                    fresh.setCalcTick(0L);
                    LocalDateTime now = LocalDateTime.now();
                    fresh.setCreatedAt(now);
                    fresh.setUpdatedAt(now);
                    return complianceRepository.saveAndFlush(fresh);
                });

        LocalDateTime now = LocalDateTime.now();
        AdEvaluation.Result eval = AdEvaluation.evaluate(rev, aircraft, LocalDate.now());

        // 复核凭证：失效的关联打标志（不删行）；SUPERSEDED 版本不进入这里
        refreshLinkValidity(c.getId(), aircraftId);

        String oldStatus = c.getStatus();
        String newStatus;
        String pendingReasons = eval.pendingReasonText();

        if (!eval.applicable()) {
            newStatus = MroDict.AD_NOT_APPLICABLE;
        } else if (MroDict.AD_PENDING.equals(eval.status())) {
            newStatus = MroDict.AD_PENDING;
        } else {
            // 到期类状态：先看落实凭证是否仍然成立。
            // 漂移复核模式（strict=false）：只有"曾经落实过"的飞机才做凭证校验——
            // 从未落实的飞机证据本就缺失，应保持引擎算出的未到期/临期/超期，不能误判待核实。
            Credential credential = checkCredential(c.getId(), aircraftId, false);
            if (credential.complied) {
                newStatus = MroDict.AD_COMPLIED;
                pendingReasons = null;
            } else if (!credential.reasons.isEmpty()) {
                // 曾经落实过（有历史关联/证据）但凭证失效 → 待核实并给出原因，绝不误判成已落实/不适用
                newStatus = MroDict.AD_PENDING;
                List<String> all = new ArrayList<>(eval.pendingReasons());
                all.addAll(credential.reasons);
                pendingReasons = String.join("；", all);
            } else {
                newStatus = eval.status();
            }
        }

        c.setApplicable(eval.applicable() ? 1 : 0);
        c.setStatus(newStatus);
        c.setPendingReasons(pendingReasons);
        c.setLimitingDim(MroDict.AD_COMPLIED.equals(newStatus) ? c.getLimitingDim() : eval.limitingDim());
        c.setDaysToDue(eval.daysToDue());
        c.setFhRemaining(eval.fhRemaining());
        c.setCycleRemaining(eval.cycleRemaining());
        c.setCalcTick(c.getCalcTick() + 1);
        c.setCalcAt(now);
        if (!MroDict.AD_COMPLIED.equals(newStatus)) {
            // 非已落实状态保留 compliedAt/compliedBy 不动：留下"曾经落实过"的历史痕迹
        }
        c.setUpdatedAt(now);
        c = complianceRepository.save(c);

        if (emitEvent && !newStatus.equals(oldStatus)) {
            event(rev.getDirectiveId(), revisionId, c.getId(), aircraftId, "STATUS_CHANGE",
                    "SYSTEM", reason == null ? "数据变化触发重算" : reason, oldStatus, newStatus, now);
        }
        AdDirective d = directiveRepository.findById(rev.getDirectiveId()).orElse(null);
        return toComplianceView(c, rev, d, aircraft, validCounts(c.getId()));
    }

    /** 凭证复核结果 */
    private record Credential(boolean complied, boolean everHad, List<String> reasons) {
    }

    /**
     * 落实凭证是否仍成立：
     * 至少一张【同机、已关闭】且未失效的工卡关联 + 至少一条未作废证据。
     *
     * @param strict true=登记落实的最终校验（即使此前没有任何凭证，缺工卡/缺证据也要报错）；
     *               false=数据漂移复核（只有曾经有过凭证、现在不成立才给原因 → 回待核实）
     */
    private Credential checkCredential(Long complianceId, Long aircraftId, boolean strict) {
        List<AdComplianceLink> links = linkRepository.findAllByComplianceId(complianceId);
        List<AdEvidence> evidences = evidenceRepository
                .findAllByComplianceIdOrderByRegisteredAtAscIdAsc(complianceId);
        boolean everLink = !links.isEmpty();
        boolean everEvidence = !evidences.isEmpty();

        int validLinks = 0;
        List<String> reasons = new ArrayList<>();
        List<String> killed = new ArrayList<>();
        for (AdComplianceLink link : links) {
            if (link.getValidFlag() == 0) {
                killed.add("工卡 " + link.getCardNo() + "（" + nz(link.getInvalidReason(), "关联已失效") + "）");
                continue;
            }
            WorkCard card = workCardRepository.findById(link.getWorkCardId()).orElse(null);
            if (card == null) {
                killed.add("工卡 " + link.getCardNo() + "（已不存在）");
                markLinkInvalid(link, "关联工卡已不存在");
                continue;
            }
            if (!card.getAircraftId().equals(aircraftId)) {
                String reason = "工卡已改挂到其他飞机（当前机号见工卡台账）";
                killed.add("工卡 " + link.getCardNo() + "（" + reason + "）");
                markLinkInvalid(link, reason);
                continue;
            }
            if ("CANCELED".equals(card.getStatus())) {
                killed.add("工卡 " + link.getCardNo() + "（已被取消）");
                markLinkInvalid(link, "工卡已被取消");
                continue;
            }
            if (!"CLOSED".equals(card.getStatus())) {
                killed.add("工卡 " + link.getCardNo() + "（关闭后被重新打开，当前"
                        + MroDict.cardStatusName(card.getStatus()) + "）");
                markLinkInvalid(link, "工卡关闭后被重新打开（当前" + MroDict.cardStatusName(card.getStatus()) + "）");
                continue;
            }
            validLinks++;
        }
        int validEvidence = 0;
        List<String> voided = new ArrayList<>();
        for (AdEvidence e : evidences) {
            if (e.getVoidFlag() == 1) {
                voided.add("证据「" + e.getTitle() + "」已作废");
            } else {
                validEvidence++;
            }
        }

        boolean everHad = everLink || everEvidence;
        if (validLinks == 0 && everLink) {
            reasons.add("原落实工卡已失效：" + String.join("、", killed));
        }
        if (validEvidence == 0 && everEvidence) {
            reasons.add("落实证据缺失：" + (voided.isEmpty() ? "有效证据不足" : String.join("、", voided)));
        }
        if (strict) {
            // 登记落实的严格校验：此前没有凭证也要给出明确原因
            if (validLinks == 0 && reasons.stream().noneMatch(r -> r.contains("工卡"))) {
                reasons.add(everLink
                        ? "没有有效的同机已关闭工卡关联：" + String.join("、", killed)
                        : "缺少至少一张属于同一架飞机且已关闭的工卡");
            }
            if (validEvidence == 0 && reasons.stream().noneMatch(r -> r.contains("证据"))) {
                reasons.add("缺少可追溯的落实证据");
            }
        }
        boolean complied = validLinks > 0 && validEvidence > 0;
        return new Credential(complied, everHad, reasons);
    }

    /** 重算开始时同步关联状态：取消/重开/改挂的工卡关联置失效（幂等）。 */
    private void refreshLinkValidity(Long complianceId, Long aircraftId) {
        for (AdComplianceLink link : linkRepository.findAllByComplianceId(complianceId)) {
            if (link.getValidFlag() == 0) {
                continue;
            }
            WorkCard card = workCardRepository.findById(link.getWorkCardId()).orElse(null);
            if (card == null) {
                markLinkInvalid(link, "关联工卡已不存在");
            } else if (!card.getAircraftId().equals(aircraftId)) {
                markLinkInvalid(link, "工卡已改挂到其他飞机（当前机号见工卡台账）");
            } else if ("CANCELED".equals(card.getStatus())) {
                markLinkInvalid(link, "工卡已被取消");
            } else if (!"CLOSED".equals(card.getStatus())) {
                markLinkInvalid(link, "工卡关闭后被重新打开（当前" + MroDict.cardStatusName(card.getStatus()) + "）");
            }
        }
    }

    /** 旧版本（SUPERSEDED）冻结：只更新关联失效标志用于历史展示，不重算状态。 */
    private void refreshLinksForFrozen(Long complianceId, String reason) {
        for (AdComplianceLink link : linkRepository.findAllByComplianceId(complianceId)) {
            if (link.getValidFlag() == 0) {
                continue;
            }
            WorkCard card = workCardRepository.findById(link.getWorkCardId()).orElse(null);
            if (card == null) {
                markLinkInvalid(link, "关联工卡已不存在（旧版冻结，仅更新凭证标志）");
            } else if (!card.getAircraftId().equals(link.getLinkedAircraftId())) {
                markLinkInvalid(link, "工卡已改挂到其他飞机（旧版冻结，仅更新凭证标志）");
            } else if ("CANCELED".equals(card.getStatus())) {
                markLinkInvalid(link, "工卡已被取消（旧版冻结，仅更新凭证标志）");
            } else if (!"CLOSED".equals(card.getStatus())) {
                markLinkInvalid(link, "工卡关闭后被重新打开（旧版冻结，仅更新凭证标志）");
            }
        }
    }

    private void markLinkInvalid(AdComplianceLink link, String reason) {
        link.setValidFlag(0);
        link.setInvalidReason(reason);
        link.setInvalidatedAt(LocalDateTime.now());
        linkRepository.save(link);
    }

    private boolean hasCredentialHistory(Long complianceId) {
        return !linkRepository.findAllByComplianceId(complianceId).isEmpty()
                || !evidenceRepository.findAllByComplianceIdOrderByRegisteredAtAscIdAsc(complianceId).isEmpty();
    }

    // ============================== 落实登记 / 证据 ==============================

    @Transactional
    public Views.AdComplianceDetail comply(Long complianceId, AdDtos.ComplyReq req) {
        AdCompliance c = complianceRepository.findByIdForUpdate(complianceId)
                .orElseThrow(() -> new BizException("落实清单条目不存在：id=" + complianceId));
        AdRevision rev = revisionRepository.findById(c.getRevisionId())
                .orElseThrow(() -> new BizException("修订版不存在"));
        if ("SUPERSEDED".equals(rev.getStatus())) {
            throw new BizException("该修订版已被新版本取代并冻结，不能在旧版本上登记落实；请对最新修订版登记");
        }
        if (!c.getApplicable().equals(1)) {
            throw new BizException("该飞机不在指令适用范围内（不适用），不能登记落实");
        }
        Aircraft aircraft = aircraftRepository.findById(c.getAircraftId())
                .orElseThrow(() -> new BizException("飞机不存在"));
        if (MroDict.AD_PENDING.equals(c.getStatus()) && c.getPendingReasons() != null
                && c.getPendingReasons().contains("阈值")) {
            throw new BizException("指令阈值配置存在待核实问题，请先由发布方更正后再登记落实");
        }

        LocalDateTime now = LocalDateTime.now();
        List<Long> cardIds = req.getWorkCardIds() == null ? List.of() : req.getWorkCardIds().stream().distinct().toList();
        if (cardIds.isEmpty()) {
            throw new BizException("落实必须关联至少一张工卡");
        }
        int newlyLinked = 0;
        List<String> linkedNos = new ArrayList<>();
        for (Long cardId : cardIds) {
            WorkCard card = workCardRepository.findById(cardId)
                    .orElseThrow(() -> new BizException("工卡不存在：id=" + cardId));
            if (!card.getAircraftId().equals(aircraft.getId())) {
                throw new BizException("工卡 " + card.getCardNo() + " 不属于本机 " + aircraft.getTailNo()
                        + "（当前属于 " + tailOf(card.getAircraftId()) + "），不能作为本机落实凭证");
            }
            if (!"CLOSED".equals(card.getStatus())) {
                throw new BizException("工卡 " + card.getCardNo() + " 当前为「"
                        + MroDict.cardStatusName(card.getStatus()) + "」，只有已关闭工卡可以作为落实凭证");
            }
            // (清单, 工卡) 唯一：重复关联同一张工卡不产生第二条记录，直接幂等复用
            AdComplianceLink link = linkRepository.findAllByComplianceId(c.getId()).stream()
                    .filter(l -> l.getWorkCardId().equals(cardId)).findFirst().orElse(null);
            if (link == null) {
                link = new AdComplianceLink();
                link.setComplianceId(c.getId());
                link.setWorkCardId(cardId);
                link.setCardNo(card.getCardNo());
                link.setLinkedAircraftId(card.getAircraftId());
                link.setLinkedBy(req.getOperator().trim());
                link.setLinkedAt(now);
                link.setValidFlag(1);
                linkRepository.save(link);
                newlyLinked++;
            } else if (link.getValidFlag() == 0) {
                // 历史失效关联重新用于落实（工卡恢复关闭且仍在本机）：恢复为有效
                link.setValidFlag(1);
                link.setInvalidReason(null);
                link.setInvalidatedAt(null);
                link.setLinkedBy(req.getOperator().trim());
                link.setLinkedAt(now);
                linkRepository.save(link);
                newlyLinked++;
            }
            linkedNos.add(card.getCardNo());
        }

        int evidenceAdded = 0;
        if (req.getEvidences() != null) {
            for (AdDtos.EvidenceItem item : req.getEvidences()) {
                if (item.getTitle() == null || item.getTitle().isBlank()
                        || item.getEvidenceRef() == null || item.getEvidenceRef().isBlank()) {
                    throw new BizException("每条证据都需要标题与可追溯的登记号/引用");
                }
                AdEvidence e = new AdEvidence();
                e.setComplianceId(c.getId());
                e.setEvidenceType("REGISTER");
                e.setTitle(item.getTitle().trim());
                e.setEvidenceRef(item.getEvidenceRef().trim());
                e.setRegisteredBy(req.getOperator().trim());
                e.setRegisteredAt(now);
                e.setVoidFlag(0);
                evidenceRepository.save(e);
                evidenceAdded++;
            }
        }

        // 最终复核：至少一张有效关闭工卡 + 至少一条有效证据
        Credential credential = checkCredential(c.getId(), aircraft.getId(), true);
        if (!credential.complied()) {
            throw new BizException("落实未生效：" + String.join("；", credential.reasons()));
        }

        String oldStatus = c.getStatus();
        c.setStatus(MroDict.AD_COMPLIED);
        c.setPendingReasons(null);
        c.setCompliedAt(now);
        c.setCompliedBy(req.getOperator().trim());
        c.setCalcTick(c.getCalcTick() + 1);
        c.setCalcAt(now);
        c.setUpdatedAt(now);
        c = complianceRepository.save(c);

        if (newlyLinked > 0) {
            event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "LINK",
                    req.getOperator(), "关联落实工卡 " + String.join("、", linkedNos)
                            + "（同机已关闭，重复关联不重复生成记录）", oldStatus, MroDict.AD_COMPLIED, now);
        }
        if (evidenceAdded > 0) {
            event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "EVIDENCE",
                    req.getOperator(), "登记落实证据 " + evidenceAdded + " 条", oldStatus, MroDict.AD_COMPLIED, now);
        }
        event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "COMPLY",
                req.getOperator(), "落实成立：关联同机关闭工卡并具备可追溯证据，状态转为已落实",
                oldStatus, MroDict.AD_COMPLIED, now);
        return complianceDetail(c.getId());
    }

    /** 追加登记证据（已落实后补证据，或落实前先挂证据） */
    @Transactional
    public Views.AdComplianceDetail registerEvidence(Long complianceId, AdDtos.RegisterEvidenceReq req) {
        AdCompliance c = complianceRepository.findByIdForUpdate(complianceId)
                .orElseThrow(() -> new BizException("落实清单条目不存在：id=" + complianceId));
        AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        AdEvidence e = new AdEvidence();
        e.setComplianceId(c.getId());
        e.setEvidenceType("REGISTER");
        e.setTitle(req.getTitle().trim());
        e.setEvidenceRef(req.getEvidenceRef().trim());
        e.setRegisteredBy(req.getOperator().trim());
        e.setRegisteredAt(now);
        e.setVoidFlag(0);
        evidenceRepository.save(e);
        event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "EVIDENCE",
                req.getOperator(), "登记落实证据：" + e.getTitle() + "（" + e.getEvidenceRef() + "）",
                null, c.getStatus(), now);
        // 追加证据可能让"证据缺失"的待核实收敛
        recomputeLocked(rev.getId(), c.getAircraftId(), "EVIDENCE", true, "追加落实证据后复核");
        return complianceDetail(c.getId());
    }

    /** 上传文件证据（文件已由 controller 落盘，这里只登记可追溯记录） */
    @Transactional
    public Views.AdComplianceDetail attachFileEvidence(Long complianceId, String fileName, String storedPath,
                                                       String operator) {
        AdCompliance c = complianceRepository.findByIdForUpdate(complianceId)
                .orElseThrow(() -> new BizException("落实清单条目不存在：id=" + complianceId));
        AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        AdEvidence e = new AdEvidence();
        e.setComplianceId(c.getId());
        e.setEvidenceType("FILE");
        e.setTitle(fileName);
        e.setEvidenceRef(storedPath);
        e.setFileName(fileName);
        e.setRegisteredBy(operator);
        e.setRegisteredAt(now);
        e.setVoidFlag(0);
        evidenceRepository.save(e);
        event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "EVIDENCE",
                operator, "上传落实证据文件：" + fileName, null, c.getStatus(), now);
        recomputeLocked(rev.getId(), c.getAircraftId(), "EVIDENCE", true, "上传落实证据后复核");
        return complianceDetail(c.getId());
    }

    @Transactional
    public Views.AdComplianceDetail voidEvidence(Long evidenceId, AdDtos.VoidEvidenceReq req) {
        AdEvidence e = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BizException("证据不存在：id=" + evidenceId));
        AdCompliance c = complianceRepository.findByIdForUpdate(e.getComplianceId())
                .orElseThrow(() -> new BizException("落实清单条目不存在"));
        AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        if (e.getVoidFlag() == 1) {
            throw new BizException("证据已处于作废状态，不能重复作废（记录保留）");
        }
        e.setVoidFlag(1);
        e.setVoidReason(req.getReason().trim());
        e.setVoidedAt(now);
        evidenceRepository.save(e);
        event(rev.getDirectiveId(), rev.getId(), c.getId(), c.getAircraftId(), "EVIDENCE_VOID",
                req.getOperator(), "证据「" + e.getTitle() + "」作废：" + req.getReason(),
                c.getStatus(), MroDict.AD_PENDING, now);
        recomputeLocked(rev.getId(), c.getAircraftId(), "EVIDENCE", true, "落实证据作废后复核");
        return complianceDetail(c.getId());
    }

    /** 手动对单条清单发起即时重算（用户核实完数据后用） */
    @Transactional
    public Views.AdComplianceView recomputeOne(Long complianceId, String operator) {
        AdCompliance c = complianceRepository.findById(complianceId)
                .orElseThrow(() -> new BizException("落实清单条目不存在：id=" + complianceId));
        AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElseThrow();
        if ("SUPERSEDED".equals(rev.getStatus())) {
            throw new BizException("该修订版已被取代冻结，历史结果不参与重算");
        }
        Views.AdComplianceView v = recomputeLocked(rev.getId(), c.getAircraftId(),
                "MANUAL", true, operator + " 手动发起单条复核");
        return v;
    }

    /** 单条证据读取（下载用） */
    @Transactional(readOnly = true)
    public Views.AdEvidenceView evidence(Long evidenceId) {
        AdEvidence e = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BizException("证据不存在：id=" + evidenceId));
        return toEvidenceView(e);
    }

    // ============================== 批量重算作业（异步、持久化、可续跑） ==============================

    @PostConstruct
    public void resumeInterruptedJobsOnStartup() {
        // 容器重启后 RUNNING 作业由 worker 续跑（明细状态都已持久化，不会重头再来，也不会重复生成结果）
        for (AdRecalcJob job : jobRepository.findAllByStatus("RUNNING")) {
            scheduleAfterCommit(() -> self.runJob(job.getId(), true));
        }
    }

    @PreDestroy
    public void shutdown() {
        batchExecutor.shutdown();
    }

    @Transactional
    public Views.AdRecalcJobView createRevisionJob(Long revisionId, String reason, String operator,
                                                   List<Long> failInject) {
        AdRevision rev = requireRevision(revisionId);
        return createJob("REVISION", rev.getDirectiveId(), revisionId, null, reason, operator, failInject);
    }

    @Transactional
    public Views.AdRecalcJobView createJob(AdDtos.RecalcJobReq req) {
        String scope = req.getScopeType() == null || req.getScopeType().isBlank()
                ? "REVISION" : req.getScopeType().trim().toUpperCase();
        Long revisionId = req.getRevisionId();
        Long aircraftId = req.getAircraftId();
        if ("REVISION".equals(scope)) {
            if (revisionId == null) {
                throw new BizException("按指令修订版重算必须指定 revisionId");
            }
            requireRevision(revisionId);
        } else if ("AIRCRAFT".equals(scope)) {
            if (aircraftId == null) {
                throw new BizException("按飞机重算必须指定 aircraftId");
            }
            aircraftRepository.findById(aircraftId).orElseThrow(() -> new BizException("飞机不存在"));
        } else if (!"FLEET".equals(scope)) {
            throw new BizException("重算范围只能是 REVISION / FLEET / AIRCRAFT");
        }
        Long directiveId = revisionId == null ? null
                : revisionRepository.findById(revisionId).map(AdRevision::getDirectiveId).orElse(null);
        String reason = req.getReason() == null || req.getReason().isBlank() ? "手动发起批量重算" : req.getReason();
        return createJob(scope, directiveId, revisionId, aircraftId, reason,
                req.getOperator(), req.getFailInjectAircraftIds());
    }

    private Views.AdRecalcJobView createJob(String scope, Long directiveId, Long revisionId, Long aircraftId,
                                           String reason, String operator, List<Long> failInject) {
        LocalDateTime now = LocalDateTime.now();
        AdRecalcJob job = new AdRecalcJob();
        job.setScopeType(scope);
        job.setDirectiveId(directiveId);
        job.setRevisionId(revisionId);
        job.setAircraftId(aircraftId);
        job.setTriggerReason(reason);
        job.setStatus("RUNNING");
        job.setTotalCount(0);
        job.setSuccessCount(0);
        job.setFailedCount(0);
        job.setNaCount(0);
        job.setStartedAt(now);
        job.setCreatedBy(operator);
        if (failInject != null && !failInject.isEmpty()) {
            job.setFailInject(failInject.stream().map(String::valueOf)
                    .reduce((a, b) -> a + "," + b).orElse(null));
        }
        job = jobRepository.save(job);

        // 生成逐 (修订版, 飞机) 明细：REVISION=该版×全机队；FLEET=全部生效版×全机队；AIRCRAFT=全部生效版×该机
        List<AdRevision> targets;
        List<Aircraft> aircraft;
        if ("AIRCRAFT".equals(scope)) {
            targets = revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE");
            aircraft = List.of(aircraftRepository.findById(aircraftId).orElseThrow());
        } else if ("FLEET".equals(scope)) {
            targets = revisionRepository.findAllByStatusOrderByEffectiveDateDesc("EFFECTIVE");
            aircraft = aircraftRepository.findAllByOrderByTailNoAsc();
        } else {
            targets = List.of(requireRevision(revisionId));
            aircraft = aircraftRepository.findAllByOrderByTailNoAsc();
        }
        int total = 0;
        for (AdRevision r : targets) {
            for (Aircraft a : aircraft) {
                AdRecalcJobItem item = new AdRecalcJobItem();
                item.setJobId(job.getId());
                item.setRevisionId(r.getId());
                item.setAircraftId(a.getId());
                item.setStatus("PENDING");
                item.setAttemptCount(0);
                item.setUpdatedAt(now);
                itemRepository.save(item);
                total++;
            }
        }
        job.setTotalCount(total);
        jobRepository.save(job);
        if (directiveId != null) {
            event(directiveId, revisionId, null, aircraftId, "RECALC_JOB", operator,
                    "创建批量重算作业 #" + job.getId() + "，范围 " + scopeTypeName(scope)
                            + "，共 " + total + " 项", null, null, now);
        }
        Long jobId = job.getId();
        scheduleAfterCommit(() -> self.runJob(jobId, false));
        return jobView(job);
    }

    /** 续跑失败项（幂等）：已成功的项不重跑，所以不会重复生成结果。 */
    @Transactional
    public Views.AdRecalcJobView resumeJob(Long jobId) {
        AdRecalcJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new BizException("重算作业不存在：id=" + jobId));
        if ("DONE".equals(job.getStatus()) || "DONE_WITH_SKIP".equals(job.getStatus())) {
            return jobView(job);
        }
        job.setStatus("RUNNING");
        jobRepository.save(job);
        scheduleAfterCommit(() -> self.runJob(jobId, true));
        return jobView(job);
    }

    @Transactional(readOnly = true)
    public List<Views.AdRecalcJobView> jobs() {
        return jobRepository.findAllByOrderByStartedAtDescIdDesc().stream().map(this::jobView).toList();
    }

    @Transactional(readOnly = true)
    public Views.AdRecalcJobView job(Long id) {
        return jobView(jobRepository.findById(id)
                .orElseThrow(() -> new BizException("重算作业不存在：id=" + id)));
    }

    @Transactional(readOnly = true)
    public List<Views.AdRecalcItemView> jobItems(Long jobId) {
        Map<Long, Aircraft> map = aircraftMap();
        return itemRepository.findAllByJobIdOrderByIdAsc(jobId).stream().map(i -> {
            Aircraft a = map.get(i.getAircraftId());
            return new Views.AdRecalcItemView(i.getId(), i.getAircraftId(),
                    a == null ? "?" : a.getTailNo(), a == null ? "?" : a.getModel(),
                    i.getRevisionId(), i.getStatus(), jobItemStatusName(i.getStatus()),
                    i.getErrorMessage(), i.getAttemptCount(), i.getUpdatedAt());
        }).toList();
    }

    /**
     * Worker：逐机独立短事务执行。飞机飞行登记是别的短事务，不等待本作业；
     * 每项计算都在该行悲观锁内取最新数据，因此与批量重算并发时最终状态按最新数据收敛。
     *
     * @param includeFailed true=续跑（除 PENDING 外也重试 FAILED）；false=首轮（只处理 PENDING，
     *                      失败项保留为 FAILED，作业落 PARTIAL_FAILED，等用户显式续跑）
     */
    public void runJob(Long jobId, boolean includeFailed) {
        scheduleLock.lock();
        try {
            String claimed = jobRepository.findById(jobId).map(AdRecalcJob::getStatus).orElse(null);
            if (claimed == null) {
                return;
            }
            if (!"RUNNING".equals(claimed)) {
                // 已结束的作业只有显式续跑才会重新置 RUNNING
                return;
            }
            log.info("[AD-recalc] 作业 #{} 开始/续跑（重试失败项={}）", jobId, includeFailed);
            List<Long> injectIds = parseInject(jobRepository.findById(jobId).map(AdRecalcJob::getFailInject).orElse(null));
            int guard = 0;
            while (guard++ < 100000) {
                try {
                    Long claimId = claimOne(jobId, injectIds, includeFailed);
                    if (claimId == null) {
                        break;
                    }
                } catch (Exception ex) {
                    log.error("[AD-recalc] 作业 #{} 轮询处理发生致命异常，终止 worker 循环", jobId, ex);
                    break;
                }
            }
            finishJob(jobId);
            log.info("[AD-recalc] 作业 #{} 已收尾 status={}", jobId,
                    jobRepository.findById(jobId).map(AdRecalcJob::getStatus).orElse(null));
        } finally {
            scheduleLock.unlock();
        }
    }

    /** 领取并处理一个待处理项，返回复合 id；没有可处理项返回 null。 */
    private Long claimOne(Long jobId, List<Long> injectIds, boolean includeFailed) {
        AdRecalcJobItem claimed;
        try {
            claimed = self.claimNextItem(jobId, includeFailed);
        } catch (Exception e) {
            log.error("[AD-recalc] 作业 #{} 领取明细失败: {}", jobId, e.getMessage(), e);
            throw new IllegalStateException("领取重算明细失败", e);
        }
        if (claimed == null) {
            return null;
        }
        Long itemId = claimed.getId();
        boolean wasFailed = "FAILED".equals(claimed.getStatus());
        try {
            self.processItem(itemId, wasFailed, injectIds);
        } catch (Exception e) {
            log.warn("[AD-recalc] 作业 #{} 项 {} 处理异常: {}", jobId, itemId, e.getMessage());
        }
        return itemId;
    }

    /**
     * 领取一个待处理项（独立短事务）。单 worker 串行处理；
     * 首轮只领 PENDING（失败即落 FAILED 不立即重试，作业转部分失败）；续跑才额外领 FAILED。
     */
    @Transactional
    public AdRecalcJobItem claimNextItem(Long jobId, boolean includeFailed) {
        AdRecalcJobItem item = itemRepository.findFirstByJobIdAndStatusOrderByIdAsc(jobId, "PENDING")
                .orElse(null);
        if (item == null && includeFailed) {
            item = itemRepository.findFirstByJobIdAndStatusOrderByIdAsc(jobId, "FAILED").orElse(null);
        }
        if (item == null) {
            return null;
        }        item.setAttemptCount(item.getAttemptCount() + 1);
        item.setUpdatedAt(LocalDateTime.now());
        return itemRepository.save(item);
    }

    /** 处理一个明细项：独立短事务，内部对清单行加锁重算；失败只记该项，不影响其他飞机继续处理。 */
    @Transactional
    public void processItem(Long itemId, boolean wasFailed, List<Long> failInjectAircraftIds) {
        AdRecalcJobItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BizException("重算明细不存在：id=" + itemId));
        // worker 单线程串行处理（scheduleLock + 单线程池），job 计数无需行锁；
        // 用普通读取，避免短事务之间在作业行上的锁等待（MySQL/H2 都更稳）。
        AdRecalcJob job = jobRepository.findById(item.getJobId())
                .orElseThrow(() -> new BizException("重算作业不存在"));
        LocalDateTime now = LocalDateTime.now();
        try {
            // 验收用失败注入：仅在该项第一次尝试时失败，续跑即成功，且不产生任何清单写入
            if (item.getAttemptCount() <= 1 && failInjectAircraftIds != null
                    && failInjectAircraftIds.contains(item.getAircraftId())) {
                throw new BizException("注入失败：模拟该机数据暂不可用（如累计值同步超时），续跑将成功");
            }
            Views.AdComplianceView view = recomputeLocked(item.getRevisionId(), item.getAircraftId(),
                    "BATCH", false, "批量重算作业 #" + job.getId());
            item.setStatus(MroDict.AD_NOT_APPLICABLE.equals(view.status()) ? "NOT_APPLICABLE" : "SUCCESS");
            item.setErrorMessage(null);
            item.setUpdatedAt(now);
            itemRepository.save(item);
            if (MroDict.AD_NOT_APPLICABLE.equals(view.status())) {
                job.setNaCount(job.getNaCount() + 1);
            } else {
                job.setSuccessCount(job.getSuccessCount() + 1);
            }
        } catch (Exception e) {
            item.setStatus("FAILED");
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            item.setErrorMessage(msg.length() > 500 ? msg.substring(0, 500) : msg);
            item.setUpdatedAt(now);
            itemRepository.save(item);
            // 续跑重试：该项之前已计入 failed，本次又失败不重复计数；重试成功在成功分支扣回
            if (!wasFailed) {
                job.setFailedCount(job.getFailedCount() + 1);
            }
            jobRepository.save(job);
            return;
        }
        // 之前失败、本次成功：失败数扣回（计数收敛到最终事实）
        if (wasFailed) {
            job.setFailedCount(Math.max(0, job.getFailedCount() - 1));
        }
        jobRepository.save(job);
    }

    /** 全部项处理完（成功/失败/不适用都落定），汇总作业状态；部分失败保留为 PARTIAL_FAILED 待续跑。 */
    @Transactional
    public void finishJob(Long jobId) {
        AdRecalcJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null || !"RUNNING".equals(job.getStatus())) {
            return;
        }
        int pending = itemRepository.findAllByJobIdAndStatus(jobId, "PENDING").size();
        int failed = itemRepository.findAllByJobIdAndStatus(jobId, "FAILED").size();
        LocalDateTime now = LocalDateTime.now();
        if (pending > 0) {
            return; // 还有没处理完的（理论上不会，防御性保留）
        }
        if (failed > 0) {
            job.setStatus("PARTIAL_FAILED");
        } else {
            job.setStatus(job.getNaCount() > 0 ? "DONE_WITH_SKIP" : "DONE");
        }
        job.setFinishedAt(now);
        jobRepository.save(job);
        if (job.getDirectiveId() != null) {
            event(job.getDirectiveId(), job.getRevisionId(), null, job.getAircraftId(), "RECALC_JOB",
                    job.getCreatedBy(),
                    "批量重算作业 #" + jobId + " " + ("PARTIAL_FAILED".equals(job.getStatus()) ? "部分失败" : "完成")
                            + "：共 " + job.getTotalCount() + " 项，成功 " + job.getSuccessCount()
                            + "，不适用 " + job.getNaCount() + "，失败 " + job.getFailedCount()
                            + "（失败项可续跑，不重复生成结果）",
                    null, null, now);
        }
    }

    /** 启动引导保存明细项用（同包） */
    public AdRecalcJobItemRepository getItemRepository() {
        return itemRepository;
    }

    /** 启动引导在事务提交后调度基线作业 */
    public void scheduleBaseline(Long jobId) {
        scheduleAfterCommit(() -> self.runJob(jobId, false));
    }

    // ============================== 视图组装 / 工具 ==============================

    private void event(Long directiveId, Long revisionId, Long complianceId, Long aircraftId,
                       String type, String operator, String detail, String from, String to, LocalDateTime at) {
        AdEvent e = new AdEvent();
        e.setDirectiveId(directiveId);
        e.setRevisionId(revisionId);
        e.setComplianceId(complianceId);
        e.setAircraftId(aircraftId);
        e.setEventType(type);
        e.setOperator(operator == null || operator.isBlank() ? "SYSTEM" : operator);
        e.setDetail(detail.length() > 512 ? detail.substring(0, 512) : detail);
        e.setFromStatus(from);
        e.setToStatus(to);
        e.setEventAt(at);
        eventRepository.save(e);
    }

    private void scheduleAfterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    batchExecutor.submit(runnable);
                }
            });
        } else {
            // 无活动事务（如启动引导 @PostConstruct）：直接异步执行
            batchExecutor.submit(runnable);
        }
    }

    private Map<Long, Aircraft> aircraftMap() {
        Map<Long, Aircraft> map = new HashMap<>();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private int[] validCounts(Long complianceId) {
        int links = 0;
        for (AdComplianceLink l : linkRepository.findAllByComplianceId(complianceId)) {
            if (l.getValidFlag() == 1) {
                links++;
            }
        }
        int ev = 0;
        for (AdEvidence e : evidenceRepository.findAllByComplianceIdOrderByRegisteredAtAscIdAsc(complianceId)) {
            if (e.getVoidFlag() == 0) {
                ev++;
            }
        }
        return new int[]{links, ev};
    }

    private Views.AdComplianceView toComplianceView(AdCompliance c, AdRevision rev, AdDirective d,
                                                    Aircraft a, int[] counts) {
        return new Views.AdComplianceView(
                c.getId(), rev.getId(), rev.getDirectiveId(),
                d == null ? "?" : d.getAdNo(), rev.getRevisionNo(),
                d == null ? rev.getTitle() : d.getTitle(),
                a.getId(), a.getTailNo(), a.getModel(), a.getAirline(),
                c.getStatus(), MroDict.adStatusName(c.getStatus()),
                c.getApplicable() == 1, c.getPendingReasons(),
                c.getLimitingDim(), MroDict.adDimName(c.getLimitingDim()),
                c.getDaysToDue(), c.getFhRemaining(), c.getCycleRemaining(),
                a.getTotalFh(), a.getTotalCycles(),
                a.getTotalFh() == null, a.getTotalCycles() == null,
                c.getCalcTick(), c.getCalcAt(), c.getCompliedAt(), c.getCompliedBy(),
                counts[0], counts[1]);
    }

    private Views.AdRevisionView toRevisionView(String adNo, AdRevision r) {
        return new Views.AdRevisionView(
                r.getId(), r.getDirectiveId(), adNo, r.getRevisionNo(), r.getTitle(),
                r.getStatus(), MroDict.adRevisionStatusName(r.getStatus()),
                r.getEffectiveDate(), r.getApplicableModels(),
                r.getTailRangeFrom(), r.getTailRangeTo(),
                r.getDueDate(), r.getDueFh(), r.getDueCycles(),
                nz(r.getWarnDays()), nzbd(r.getWarnFh()), nz(r.getWarnCycles()),
                r.getRevisionNote(), !"DRAFT".equals(r.getStatus()), r.getPublishedAt());
    }

    private Views.AdEvidenceView toEvidenceView(AdEvidence e) {
        return new Views.AdEvidenceView(e.getId(), e.getEvidenceType(),
                "FILE".equals(e.getEvidenceType()) ? "上传文件" : "登记证据",
                e.getTitle(), e.getEvidenceRef(), e.getFileName(),
                e.getRegisteredBy(), e.getRegisteredAt(),
                e.getVoidFlag() == 1, e.getVoidReason(), e.getVoidedAt());
    }

    private Views.AdEventView toEventView(AdEvent e) {
        return new Views.AdEventView(e.getId(), e.getEventType(), eventName(e.getEventType()),
                e.getRevisionId(), e.getComplianceId(), e.getAircraftId(),
                e.getOperator(), e.getDetail(),
                e.getFromStatus(), MroDict.adStatusName(e.getFromStatus()),
                e.getToStatus(), MroDict.adStatusName(e.getToStatus()), e.getEventAt());
    }

    private Views.AdRecalcJobView jobView(AdRecalcJob j) {
        int total = Math.max(1, j.getTotalCount());
        int done = j.getSuccessCount() + j.getFailedCount() + j.getNaCount();
        int percent = Math.min(100, done * 100 / total);
        AdRevision rev = j.getRevisionId() == null ? null
                : revisionRepository.findById(j.getRevisionId()).orElse(null);
        AdDirective d = j.getDirectiveId() == null ? null
                : directiveRepository.findById(j.getDirectiveId()).orElse(null);
        Aircraft a = j.getAircraftId() == null ? null
                : aircraftRepository.findById(j.getAircraftId()).orElse(null);
        return new Views.AdRecalcJobView(
                j.getId(), j.getScopeType(), scopeTypeName(j.getScopeType()),
                j.getDirectiveId(), d == null ? null : d.getAdNo(),
                rev == null ? null : rev.getRevisionNo(), j.getRevisionId(),
                j.getAircraftId(), a == null ? null : a.getTailNo(),
                j.getTriggerReason(), j.getStatus(), jobStatusName(j.getStatus()),
                j.getTotalCount(), j.getSuccessCount(), j.getFailedCount(), j.getNaCount(),
                "RUNNING".equals(j.getStatus()), percent, j.getStartedAt(), j.getFinishedAt(),
                j.getCreatedBy());
    }

    private String jobStatusName(String s) {
        return switch (s) {
            case "RUNNING" -> "运行中";
            case "DONE" -> "全部成功";
            case "PARTIAL_FAILED" -> "部分失败（可续跑）";
            case "DONE_WITH_SKIP" -> "完成（含不适用）";
            default -> s;
        };
    }

    private String jobItemStatusName(String s) {
        return switch (s) {
            case "PENDING" -> "待处理";
            case "SUCCESS" -> "成功";
            case "FAILED" -> "失败";
            case "NOT_APPLICABLE" -> "不适用";
            default -> s;
        };
    }

    private String scopeTypeName(String s) {
        return switch (s) {
            case "REVISION" -> "按指令修订版";
            case "FLEET" -> "全机队";
            case "AIRCRAFT" -> "单架飞机";
            default -> s;
        };
    }

    private String eventName(String type) {
        return switch (type) {
            case "REVISION_PUBLISH" -> "修订版发布/建档";
            case "STATUS_CHANGE" -> "状态变化";
            case "COMPLY" -> "登记落实";
            case "LINK" -> "关联工卡";
            case "EVIDENCE" -> "证据登记";
            case "EVIDENCE_VOID" -> "证据作废";
            case "RECALC_JOB" -> "批量重算";
            case "RECALC" -> "重算";
            default -> type;
        };
    }

    private AdDirective requireDirective(Long id) {
        return directiveRepository.findById(id)
                .orElseThrow(() -> new BizException("适航指令不存在：id=" + id));
    }

    private AdRevision requireRevision(Long id) {
        return revisionRepository.findById(id)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + id));
    }

    private AdRevision currentRevision(List<AdRevision> revs) {
        AdRevision effective = null;
        AdRevision draft = null;
        AdRevision latest = null;
        for (AdRevision r : revs) {
            if ("EFFECTIVE".equals(r.getStatus())) {
                effective = r;
            }
            if ("DRAFT".equals(r.getStatus())) {
                draft = r;
            }
            latest = r;
        }
        return effective != null ? effective : (draft != null ? draft : latest);
    }

    private String tailOf(Long aircraftId) {
        return aircraftRepository.findById(aircraftId).map(Aircraft::getTailNo).orElse("?");
    }

    private List<Long> parseInject(String raw) {
        List<Long> ids = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return ids;
        }
        for (String p : raw.split(",")) {
            try {
                ids.add(Long.parseLong(p.trim()));
            } catch (NumberFormatException ignore) {
                // 跳过脏数据
            }
        }
        return ids;
    }

    private String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private BigDecimal nzbd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
