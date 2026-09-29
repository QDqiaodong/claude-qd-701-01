package com.aircraft.mro.ad.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.aircraft.mro.ad.domain.AdCompliance;
import com.aircraft.mro.ad.domain.AdDirective;
import com.aircraft.mro.ad.domain.AdEvent;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.dto.AdDtos;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.repository.AdBatchJobRepository;
import com.aircraft.mro.ad.repository.AdComplianceRepository;
import com.aircraft.mro.ad.repository.AdDirectiveRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.repository.AircraftRepository;

/**
 * 指令目录、修订版、适用范围与阈值的写操作与查询入口。
 *
 * <p>修订规则：新增修订版时旧版置 SUPERSEDED（其判断结果与证据冻结保留），
 * 新版按新适用范围/阈值重算覆盖机队；编辑当前版的范围/阈值则只重算当前版。
 * 所有机队级重算都是后台异步任务，范围/进度/成功失败由 {@link AdBatchService} 持久化。
 */
@Service
public class AdDirectiveService {

    private final AdDirectiveRepository directiveRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdScopeRepository scopeRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final AdBatchJobRepository batchJobRepository;
    private final AdBatchService batchService;
    private final AdStatusEngine engine;
    private final AdViewAssembler assembler;
    private final AdDirectiveService self;

    public AdDirectiveService(AdDirectiveRepository directiveRepository,
                              AdRevisionRepository revisionRepository,
                              AdScopeRepository scopeRepository,
                              AdComplianceRepository complianceRepository,
                              AdEventRepository eventRepository,
                              AircraftRepository aircraftRepository,
                              AdBatchJobRepository batchJobRepository,
                              AdBatchService batchService,
                              AdStatusEngine engine,
                              AdViewAssembler assembler,
                              @Lazy AdDirectiveService self) {
        this.directiveRepository = directiveRepository;
        this.revisionRepository = revisionRepository;
        this.scopeRepository = scopeRepository;
        this.complianceRepository = complianceRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.batchJobRepository = batchJobRepository;
        this.batchService = batchService;
        this.engine = engine;
        this.assembler = assembler;
        this.self = self;
    }

    // ============================== 读 ==============================

    @Transactional(readOnly = true)
    public List<AdViews.DirectiveView> listDirectives() {
        return assembler.directives();
    }

    @Transactional(readOnly = true)
    public List<AdViews.RevisionView> listRevisions(Long directiveId) {
        return assembler.revisions(directiveId);
    }

    @Transactional(readOnly = true)
    public AdViews.RevisionView revision(Long revisionId) {
        AdRevision rev = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        return assembler.toRevisionView(rev, null);
    }

    // ============================== 新建指令（首版） ==============================

    @Transactional
    public AdViews.RevisionView create(AdDtos.CreateDirectiveReq req) {
        String adNo = req.getAdNo().trim();
        if (directiveRepository.existsByAdNo(adNo)) {
            throw new BizException("适航指令编号 " + adNo + " 已存在");
        }
        LocalDateTime now = LocalDateTime.now();
        AdDirective directive = new AdDirective();
        directive.setAdNo(adNo);
        directive.setTitle(req.getTitle().trim());
        directive.setIssuer(req.getIssuer().trim());
        directive.setCreatedAt(now);
        directive.setUpdatedAt(now);
        directive = directiveRepository.save(directive);

        AdRevision rev = new AdRevision();
        rev.setDirectiveId(directive.getId());
        rev.setRevisionNo(req.getRevisionNo().trim());
        rev.setEffectiveDate(req.getEffectiveDate() == null ? LocalDate.now() : req.getEffectiveDate());
        applyThresholds(rev, req.getDueDate(), req.getDueFh(), req.getDueCycles(),
                req.getWarnDays(), req.getWarnFh(), req.getWarnCycles());
        rev.setDemoFailTails(blankToNull(req.getDemoFailTails()));
        rev.setStatus("ACTIVE");
        rev.setCreatedAt(now);
        rev.setUpdatedAt(now);
        rev = revisionRepository.save(rev);
        saveScopes(rev.getId(), req.getScopes());
        rev.setConfigIssue(engine.checkConfig(rev));
        revisionRepository.save(rev);

        directive.setCurrentRevId(rev.getId());
        directiveRepository.save(directive);

        // 首版：同步为覆盖机队建立清单项（避免首屏漏行），随后后台批量任务逐架算出结果
        ensureFleetRows(rev.getId());
        recordEvent(directive.getId(), rev.getId(), null, "CREATE", "管理员",
                "发布 " + adNo + " R" + rev.getRevisionNo() + "：" + directive.getTitle(), null, now);
        triggerFleetRecomputeAfterCommit(rev.getId(), "REVISION", "管理员",
                "新指令 " + adNo + " 首次计算机队落实清单");
        return assembler.toRevisionView(rev, directive);
    }

    // ============================== 新增修订版（旧版冻结） ==============================

    @Transactional
    public AdViews.RevisionView revise(Long directiveId, AdDtos.ReviseReq req) {
        AdDirective directive = directiveRepository.findById(directiveId)
                .orElseThrow(() -> new BizException("适航指令不存在：id=" + directiveId));
        AdRevision current = directive.getCurrentRevId() == null ? null
                : revisionRepository.findById(directive.getCurrentRevId()).orElse(null);
        String newNo = req.getRevisionNo().trim();
        for (AdRevision existing : revisionRepository.findByDirectiveIdOrderByRevisionNoAsc(directiveId)) {
            if (existing.getRevisionNo().equalsIgnoreCase(newNo)) {
                throw new BizException("修订版号 R" + newNo + " 已存在，旧版本必须保留、不能覆盖");
            }
        }
        // 同一指令只允许一个 ACTIVE：把当前版冻结（其清单结果与证据原样保留）
        LocalDateTime now = LocalDateTime.now();
        if (current != null && "ACTIVE".equals(current.getStatus())) {
            current.setStatus("SUPERSEDED");
            current.setUpdatedAt(now);
            revisionRepository.save(current);
        }

        AdRevision rev = new AdRevision();
        rev.setDirectiveId(directive.getId());
        rev.setRevisionNo(newNo);
        rev.setEffectiveDate(req.getEffectiveDate() == null ? LocalDate.now() : req.getEffectiveDate());
        applyThresholds(rev, req.getDueDate(), req.getDueFh(), req.getDueCycles(),
                req.getWarnDays(), req.getWarnFh(), req.getWarnCycles());
        rev.setStatus("ACTIVE");
        rev.setCreatedAt(now);
        rev.setUpdatedAt(now);
        rev = revisionRepository.save(rev);
        saveScopes(rev.getId(), req.getScopes());
        rev.setConfigIssue(engine.checkConfig(rev));
        revisionRepository.save(rev);

        directive.setCurrentRevId(rev.getId());
        directive.setUpdatedAt(now);
        directiveRepository.save(directive);

        ensureFleetRows(rev.getId());
        String oldNo = current == null ? "?" : current.getRevisionNo();
        recordEvent(directive.getId(), rev.getId(), null, "REVISE", "管理员",
                "发布 R" + newNo + " 替代 R" + oldNo
                        + "：旧版本判断结果与证据冻结保留，新版本按新适用范围与阈值对覆盖机队重新计算", null, now);
        triggerFleetRecomputeAfterCommit(rev.getId(), "REVISION", "管理员",
                directive.getAdNo() + " 发布 R" + newNo + "，全机队按新规则重算");
        return assembler.toRevisionView(rev, directive);
    }

    // ============================== 编辑当前版范围 / 阈值 ==============================

    @Transactional
    public AdViews.RevisionView editRevision(Long revisionId, AdDtos.EditRevisionReq req) {
        AdRevision rev = revisionRepository.findByIdForUpdate(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        if ("SUPERSEDED".equals(rev.getStatus())) {
            throw new BizException("R" + rev.getRevisionNo() + " 已被替代，历史版本只读，结果不能被覆盖；"
                    + "请发布新修订版");
        }
        for (AdBatchJobStub job : runningJobsOf(rev.getId())) {
            throw new BizException("该修订版的批量重算 #" + job.id + " 正在进行中，请等待结束后再修改配置");
        }

        LocalDateTime now = LocalDateTime.now();
        boolean scopeChanged = req.getScopes() != null;
        boolean thresholdChanged = false;
        if (scopeChanged) {
            scopeRepository.deleteByRevisionId(rev.getId());
            scopeRepository.flush();
            saveScopes(rev.getId(), req.getScopes());
        }
        if (req.getEffectiveDate() != null) {
            rev.setEffectiveDate(req.getEffectiveDate());
        }
        // 编辑表单总是回显并提交全部字段：null 即显式清空该维度阈值
        thresholdChanged |= !eq(rev.getDueDate(), req.getDueDate());
        rev.setDueDate(req.getDueDate());
        thresholdChanged |= !eq(rev.getDueFh(), req.getDueFh());
        rev.setDueFh(req.getDueFh());
        thresholdChanged |= !eq(rev.getDueCycles(), req.getDueCycles());
        rev.setDueCycles(req.getDueCycles());
        thresholdChanged |= !eq(rev.getWarnDays(), req.getWarnDays());
        rev.setWarnDays(req.getWarnDays());
        thresholdChanged |= !eq(rev.getWarnFh(), req.getWarnFh());
        rev.setWarnFh(req.getWarnFh());
        thresholdChanged |= !eq(rev.getWarnCycles(), req.getWarnCycles());
        rev.setWarnCycles(req.getWarnCycles());
        String issue = engine.checkConfig(rev);
        thresholdChanged |= !eq(rev.getConfigIssue(), issue);
        rev.setConfigIssue(issue);
        rev.setUpdatedAt(now);
        revisionRepository.saveAndFlush(rev);

        directiveRepository.findById(rev.getDirectiveId()).ifPresent(d -> {
            d.setUpdatedAt(now);
            directiveRepository.save(d);
        });

        if (!scopeChanged && !thresholdChanged) {
            return assembler.toRevisionView(rev, null);
        }
        ensureFleetRows(rev.getId());
        String trigger = scopeChanged ? "SCOPE" : "THRESHOLD";
        recordEvent(rev.getDirectiveId(), rev.getId(), null,
                scopeChanged ? "SCOPE_CHANGE" : "THRESHOLD_CHANGE", "管理员",
                (scopeChanged ? "适用范围已修改" : "截止阈值/临期窗口已修改")
                        + (issue == null ? "" : "；当前配置存在矛盾，命中飞机将进入待核实：" + issue)
                        + "，覆盖机队重新计算", null, now);
        triggerFleetRecomputeAfterCommit(rev.getId(), trigger, "管理员",
                scopeChanged ? "适用范围变更，覆盖机队重算" : "截止阈值变更，覆盖机队重算");
        return assembler.toRevisionView(rev, null);
    }

    // ============================== 手动触发批量重算 ==============================

    @Transactional
    public void manualRecompute(Long revisionId, String operator) {
        AdRevision rev = revisionRepository.findByIdForUpdate(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        if (!"ACTIVE".equals(rev.getStatus())) {
            throw new BizException("被替代的历史版本结果冻结，不能重算");
        }
        ensureFleetRows(rev.getId());
        String op = operator == null || operator.isBlank() ? "管理员" : operator.trim();
        recordEvent(rev.getDirectiveId(), rev.getId(), null, "RECOMPUTE", op,
                "手动发起覆盖机队批量重算", null, LocalDateTime.now());
        // 事务提交后再入队：后台任务读到的一定是已提交配置，HTTP 请求不等待逐架重算
        triggerFleetRecomputeAfterCommit(rev.getId(), "MANUAL", op, "页面手动发起重算");
    }

    // ============================== 内部 ==============================

    private void applyThresholds(AdRevision rev, LocalDate dueDate, BigDecimal dueFh, Integer dueCycles,
                                 Integer warnDays, BigDecimal warnFh, Integer warnCycles) {
        if (dueDate == null && (dueFh == null || dueFh.compareTo(BigDecimal.ZERO) <= 0)
                && (dueCycles == null || dueCycles <= 0)) {
            throw new BizException("日历日期、累计飞行小时、累计起落次数三类截止阈值至少要设置一类");
        }
        if (dueFh != null && dueFh.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("飞行小时截止阈值必须大于 0");
        }
        if (dueCycles != null && dueCycles <= 0) {
            throw new BizException("起落次数截止阈值必须大于 0");
        }
        if (warnDays != null && warnDays < 0) {
            throw new BizException("临期提前天数不能为负数");
        }
        if (warnFh != null && warnFh.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("临期提前飞行小时不能为负数");
        }
        if (warnCycles != null && warnCycles < 0) {
            throw new BizException("临期提前起落次数不能为负数");
        }
        rev.setDueDate(dueDate);
        rev.setDueFh(dueFh);
        rev.setDueCycles(dueCycles);
        rev.setWarnDays(warnDays);
        rev.setWarnFh(warnFh);
        rev.setWarnCycles(warnCycles);
    }

    private void saveScopes(Long revisionId, List<AdDtos.ScopeReq> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new BizException("适用范围至少要配置一行机型（机号边界可空=该机型全部）");
        }
        int seq = 1;
        for (AdDtos.ScopeReq req : scopes) {
            String model = req.getModel() == null ? "" : req.getModel().trim().toUpperCase();
            if (!MroDict.isValidModel(model)) {
                throw new BizException("适用机型只能是 B737 / A320 / A330 / ARJ21，收到：" + req.getModel());
            }
            AdScope row = new AdScope();
            row.setRevisionId(revisionId);
            row.setModel(model);
            row.setTailLower(blankToNull(req.getTailLower()));
            row.setTailUpper(blankToNull(req.getTailUpper()));
            if (row.getTailLower() != null && row.getTailUpper() != null
                    && row.getTailLower().compareTo(row.getTailUpper()) > 0) {
                throw new BizException("适用机号范围下界 " + row.getTailLower() + " 不能大于上界 " + row.getTailUpper()
                        + "（单边留空表示边界不完整，命中飞机将进入待核实）");
            }
            row.setSeqNo(seq++);
            scopeRepository.save(row);
        }
    }

    /**
     * 为修订版覆盖机队（范围提及机型的全部飞机）同步建立清单项。
     * 已落实/待核实行永远保留（即使新范围把它划出去，历史结果仍在，只是 applicable=0）。
     */
    private void ensureFleetRows(Long revisionId) {
        List<AdScope> scopes = scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(revisionId);
        LocalDateTime now = LocalDateTime.now();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            boolean modelMentioned = scopes.stream().anyMatch(s -> s.getModel().equalsIgnoreCase(a.getModel()));
            if (!modelMentioned) {
                continue;
            }
            if (complianceRepository.findByRevisionIdAndAircraftId(revisionId, a.getId()).isEmpty()) {
                AdCompliance row = new AdCompliance();
                row.setRevisionId(revisionId);
                row.setAircraftId(a.getId());
                row.setStatus(AdComplianceStatus.NOT_APPLICABLE);
                row.setApplicable(0);
                row.setFactVersion(0L);
                row.setComputedAt(now);
                row.setUpdatedAt(now);
                complianceRepository.save(row);
            }
        }
    }

    /** 事务提交后再入队后台批量任务，保证任务读到的是已提交的范围/阈值。 */
    private void triggerFleetRecomputeAfterCommit(Long revisionId, String trigger, String operator, String reason) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    batchService.enqueue(revisionId, trigger, operator, reason);
                }
            });
        } else {
            batchService.enqueue(revisionId, trigger, operator, reason);
        }
    }

    private record AdBatchJobStub(Long id) {
    }

    private List<AdBatchJobStub> runningJobsOf(Long revisionId) {
        List<AdBatchJobStub> list = new ArrayList<>();
        batchJobRepository.findByStatus("RUNNING").stream()
                .filter(j -> j.getRevisionId().equals(revisionId))
                .forEach(j -> list.add(new AdBatchJobStub(j.getId())));
        return list;
    }

    private void recordEvent(Long directiveId, Long revisionId, Long aircraftId, String type,
                             String operator, String detail, Long batchJobId, LocalDateTime at) {
        AdEvent event = new AdEvent();
        event.setDirectiveId(directiveId);
        event.setRevisionId(revisionId);
        event.setAircraftId(aircraftId);
        event.setEventType(type);
        event.setOperator(operator);
        event.setDetail(detail);
        event.setBatchJobId(batchJobId);
        event.setEventAt(at);
        eventRepository.save(event);
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
