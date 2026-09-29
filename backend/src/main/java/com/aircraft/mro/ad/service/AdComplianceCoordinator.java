package com.aircraft.mro.ad.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.aircraft.mro.ad.domain.AdCompliance;
import com.aircraft.mro.ad.domain.AdComplianceCard;
import com.aircraft.mro.ad.domain.AdEvidence;
import com.aircraft.mro.ad.domain.AdEvent;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.repository.AdComplianceCardRepository;
import com.aircraft.mro.ad.repository.AdComplianceRepository;
import com.aircraft.mro.ad.repository.AdEvidenceRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 落实结果计算与联动的核心协调器。
 *
 * <p>并发收敛模型（关键设计）：
 * <ul>
 *   <li>所有重算都以【飞机行悲观写锁】为起点，同一事务内再锁落实清单行；
 *       飞行登记 / 工卡变化 / 批量重算 / 人工核实走到同一架飞机时严格按这一顺序排队，
 *       后到者拿到锁后读到的一定是最新累计值与最新工卡状态，锁内计算锁内写回，
 *       较旧的结果物理上不可能覆盖较新的结果。</li>
 *   <li>{@code fact_version} 每次输入签名变化 +1，是“新结果覆盖旧结果”的显式判据。</li>
 *   <li>飞行登记/工卡变化走 {@code REQUIRED}（与业务动作同生共死）；
 *       后台批量 worker 走 {@code REQUIRES_NEW}（一架飞机一个短事务，互不影响，
 *       不占住普通查询与飞行登记）。</li>
 * </ul>
 */
@Service
public class AdComplianceCoordinator {

    private final AdRevisionRepository revisionRepository;
    private final AdScopeRepository scopeRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdComplianceCardRepository cardLinkRepository;
    private final AdEvidenceRepository evidenceRepository;
    private final AdEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final WorkCardRepository workCardRepository;
    private final AdStatusEngine engine;
    private final AdViewAssembler assembler;
    private final AdComplianceCoordinator self;

    private final Path evidenceDir;

    public AdComplianceCoordinator(AdRevisionRepository revisionRepository,
                                   AdScopeRepository scopeRepository,
                                   AdComplianceRepository complianceRepository,
                                   AdComplianceCardRepository cardLinkRepository,
                                   AdEvidenceRepository evidenceRepository,
                                   AdEventRepository eventRepository,
                                   AircraftRepository aircraftRepository,
                                   WorkCardRepository workCardRepository,
                                   AdStatusEngine engine,
                                   AdViewAssembler assembler,
                                   @Lazy AdComplianceCoordinator self,
                                   @Value("${mro.evidence.dir:evidence}") String evidenceBase) {
        this.revisionRepository = revisionRepository;
        this.scopeRepository = scopeRepository;
        this.complianceRepository = complianceRepository;
        this.cardLinkRepository = cardLinkRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.workCardRepository = workCardRepository;
        this.engine = engine;
        this.assembler = assembler;
        this.self = self;
        this.evidenceDir = Paths.get(evidenceBase).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.evidenceDir);
        } catch (IOException e) {
            throw new UncheckedIOException("落实证据目录不可用：" + this.evidenceDir, e);
        }
    }

    // ============================== 上游事实变化钩子 ==============================

    /**
     * 飞机登记一次飞行后调用：只重算这一架飞机在所有当前生效修订版上的清单，
     * 与飞行登记同一事务提交（飞行回滚则重算一起回滚）。
     */
    @Transactional
    public void onAircraftFactsChanged(Long aircraftId, String reason) {
        List<AdRevision> active = activeRevisions();
        for (AdRevision rev : active) {
            self.recomputeJoined(rev.getId(), aircraftId, "SYSTEM", reason, null);
        }
    }

    /**
     * 工卡状态/挂机构变化时调用（取消、重新打开、改挂飞机）。
     * 只重算真正关联到这张工卡、且当前为已落实/待核实的清单行，其余飞机不受影响。
     */
    @Transactional
    public void onWorkCardChanged(Long cardId, String reason) {
        WorkCard card = workCardRepository.findById(cardId).orElse(null);
        if (card == null) {
            return;
        }
        java.util.Set<Long> affectedComplianceIds = new java.util.HashSet<>();
        for (AdComplianceCard link : cardLinkRepository.findByCardId(cardId)) {
            affectedComplianceIds.add(link.getComplianceId());
        }
        for (Long complianceId : affectedComplianceIds) {
            AdCompliance c = complianceRepository.findById(complianceId).orElse(null);
            if (c == null) {
                continue;
            }
            AdRevision rev = revisionRepository.findById(c.getRevisionId()).orElse(null);
            if (rev == null || !"ACTIVE".equals(rev.getStatus())) {
                continue;
            }
            self.recomputeJoined(rev.getId(), c.getAircraftId(), "SYSTEM", reason, null);
        }
        // 改挂到新飞机时，新飞机也需要按最新事实算一遍（新飞机一般无关联，只是普通重算）
        for (AdRevision rev : activeRevisions()) {
            self.recomputeJoined(rev.getId(), card.getAircraftId(), "SYSTEM", reason, null);
        }
    }

    // ============================== 单机重算（两条事务传播路径） ==============================

    /** 与调用方同事务（飞行登记 / 工卡变化 / 人工核实）。 */
    @Transactional(propagation = Propagation.REQUIRED)
    public RecomputeOutcome recomputeJoined(Long revisionId, Long aircraftId, String operator,
                                            String reason, Long jobItemId) {
        return doRecompute(revisionId, aircraftId, operator, reason, jobItemId);
    }

    /** 独立短事务（后台批量 worker：一架飞机一个事务，部分失败不波及其它飞机）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RecomputeOutcome recomputeNew(Long revisionId, Long aircraftId, String operator,
                                         String reason, Long jobItemId) {
        return doRecompute(revisionId, aircraftId, operator, reason, jobItemId);
    }

    /** 重算结果（供批量 worker 记账）。 */
    public record RecomputeOutcome(String status, boolean created, boolean changed) {
    }

    private RecomputeOutcome doRecompute(Long revisionId, Long aircraftId, String operator,
                                         String reason, Long jobItemId) {
        // 1) 飞机事实行锁：并发收敛的锚点
        Aircraft aircraft = aircraftRepository.findByIdForUpdate(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        AdRevision rev = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        List<AdScope> scopes = scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(revisionId);

        // 2) 清单行锁（不存在则新建——每架飞机对每个当前修订版都有唯一一行）
        boolean created = false;
        AdCompliance row = complianceRepository
                .findByRevisionIdAndAircraftIdForUpdate(revisionId, aircraftId).orElse(null);
        if (row == null) {
            row = new AdCompliance();
            row.setRevisionId(revisionId);
            row.setAircraftId(aircraftId);
            row.setStatus(AdComplianceStatus.NOT_APPLICABLE);
            row.setApplicable(0);
            row.setFactVersion(0L);
            row.setComputedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            row = complianceRepository.save(row);
            created = true;
        }

        // 3) 纯判定
        AdStatusEngine.EvalResult eval =
                engine.evaluate(rev, scopes, aircraft, java.time.LocalDate.now());

        String oldStatus = row.getStatus();
        String newStatus;
        List<String> pendingReasons = new ArrayList<>(eval.pendingReasons());
        // 曾经做过落实（当前已落实，或已落实后因工卡/证据事实变化转待核实）的行：
        // 始终用工卡/证据有效性校验来决定结果，不能被纯期限判定覆盖。否则重新打开的工卡
        // 在服务重启重算时会被“未到期/不适用”冲掉，验收路径 7 与历史语义都不成立。
        boolean hadComply = oldStatus != null
                && (AdComplianceStatus.COMPLIED.equals(oldStatus)
                        || AdComplianceStatus.PENDING_VERIFY.equals(oldStatus))
                && !cardLinkRepository.findByComplianceId(row.getId()).isEmpty();

        if (hadComply) {
            List<String> invalid = validateComplyFacts(rev, scopes, aircraft, row);
            if (invalid.isEmpty()) {
                newStatus = AdComplianceStatus.COMPLIED;
                pendingReasons = List.of();
            } else {
                newStatus = AdComplianceStatus.PENDING_VERIFY;
                pendingReasons = invalid;
            }
        } else {
            newStatus = eval.rawStatus();
        }

        // 4) 写回（余量即使待核实也尽量展示能算出的维度）
        row.setApplicable(eval.applicable() || hadComply ? 1 : 0);
        row.setGoverningKind(AdComplianceStatus.COMPLIED.equals(newStatus) ? null : eval.governingKind());
        row.setDaysLeft(eval.daysLeft());
        row.setFhLeft(eval.fhLeft());
        row.setCyclesLeft(eval.cyclesLeft());
        row.setPendingReason(pendingReasons.isEmpty() ? null
                : clampReason(String.join("；", pendingReasons)));
        if (AdComplianceStatus.COMPLIED.equals(newStatus)) {
            if (row.getCompiledAt() == null) {
                row.setCompiledAt(LocalDateTime.now());
            }
            if (row.getCompiledBy() == null) {
                row.setCompiledBy(operator);
            }
        } else if (!hadComply) {
            // 普通期限行：清空落实痕迹；但待核实的历史落实行必须保留 compiledAt/compiledBy
            row.setCompiledAt(null);
            row.setCompiledBy(null);
        }
        if (jobItemId != null) {
            row.setLastJobItemId(jobItemId);
        }

        String sig = signature(newStatus, eval, pendingReasons, row);
        boolean changed = !sig.equals(row.getFactSig());
        LocalDateTime now = LocalDateTime.now();
        if (changed) {
            row.setFactVersion(row.getFactVersion() == null ? 1 : row.getFactVersion() + 1);
        }
        row.setFactSig(sig);
        row.setComputedAt(now);
        row.setUpdatedAt(now);
        complianceRepository.save(row);

        // 5) 状态变化留痕（新建首行不发“状态变化”事件；无变化不发事件——收敛不刷屏）
        if (!created && changed && !newStatus.equals(oldStatus)) {
            String type;
            String detail;
            if (AdComplianceStatus.COMPLIED.equals(oldStatus)
                    && AdComplianceStatus.PENDING_VERIFY.equals(newStatus)) {
                type = "CARD_CHANGED";
                detail = "已落实状态转为待核实：" + String.join("；", pendingReasons);
            } else {
                type = "STATUS_CHANGE";
                detail = reason != null ? reason : ("状态由「" + AdComplianceStatus.name(oldStatus)
                        + "」按当前事实重算为「" + AdComplianceStatus.name(newStatus) + "」");
            }
            recordEvent(rev, aircraftId, type, oldStatus, newStatus, operator, detail, null);
        }
        return new RecomputeOutcome(newStatus, created, changed);
    }

    // ============================== 已落实有效性二次校验 ==============================

    /**
     * @return 失效原因列表；空 = 落实仍然有效
     */
    private List<String> validateComplyFacts(AdRevision rev, List<AdScope> scopes,
                                             Aircraft aircraft, AdCompliance row) {
        List<String> reasons = new ArrayList<>();
        AdStatusEngine.ScopeMatch scope = engine.matchScope(aircraft, scopes);
        if (scope.ambiguous()) {
            reasons.add(scope.reason());
        }
        String configIssue = firstNonBlank(engine.checkConfig(rev), rev.getConfigIssue());
        if (configIssue != null && (scope.applicable() || scope.ambiguous())) {
            reasons.add("指令阈值互相矛盾：" + configIssue);
        }
        if (scope.applicable() || scope.ambiguous()) {
            if (rev.getDueFh() != null && aircraft.getTotalFh() == null) {
                reasons.add("缺少飞机累计飞行小时（总飞行小时未录入）");
            }
            if (rev.getDueCycles() != null && aircraft.getTotalCycles() == null) {
                reasons.add("缺少飞机累计起落次数（总起落次数未录入）");
            }
        }

        List<AdComplianceCard> links = cardLinkRepository.findByComplianceId(row.getId());
        if (links.isEmpty()) {
            reasons.add("落实未关联任何工卡");
        }
        boolean hasValidClosed = false;
        for (AdComplianceCard link : links) {
            WorkCard card = workCardRepository.findById(link.getCardId()).orElse(null);
            if (card == null) {
                reasons.add("关联工卡 " + link.getCardNo() + " 已不存在");
                continue;
            }
            if (!card.getAircraftId().equals(aircraft.getId())) {
                Aircraft other = aircraftRepository.findById(card.getAircraftId()).orElse(null);
                reasons.add("关联工卡 " + link.getCardNo() + " 已改挂到其他飞机（"
                        + (other == null ? "id=" + card.getAircraftId() : other.getTailNo()) + "），不再属于本机");
                continue;
            }
            if ("CANCELED".equals(card.getStatus())) {
                reasons.add("关联工卡 " + link.getCardNo() + " 已被取消");
                continue;
            }
            if (!"CLOSED".equals(card.getStatus())) {
                reasons.add("关联工卡 " + link.getCardNo() + " 已重新打开，当前为「"
                        + com.aircraft.mro.domain.MroDict.cardStatusName(card.getStatus()) + "」");
                continue;
            }
            hasValidClosed = true;
        }

        List<AdEvidence> evidences = evidenceRepository.findByComplianceIdOrderByCreatedAtAsc(row.getId());
        boolean hasEvidence = evidences.stream().anyMatch(e -> e.getMissing() == null || e.getMissing() == 0);
        if (!hasEvidence) {
            reasons.add("缺少可追溯的落实证据（证据为空或已被标记缺失），历史证据记录仍保留");
        }
        return reasons;
    }

    // ============================== 落实登记 ==============================

    @Transactional
    public AdViews.ComplianceView comply(Long revisionId, Long aircraftId,
                                         String operator, List<Long> cardIds,
                                         String evidenceRefNo, String evidenceNote,
                                         MultipartFile uploadFile) {
        AdRevision rev = revisionRepository.findByIdForUpdate(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        if (!"ACTIVE".equals(rev.getStatus())) {
            throw new BizException("R" + rev.getRevisionNo() + " 已被新版替代（历史冻结），请在当前生效版本上登记落实");
        }
        Aircraft aircraft = aircraftRepository.findByIdForUpdate(aircraftId)
                .orElseThrow(() -> new BizException("飞机不存在：id=" + aircraftId));
        List<AdScope> scopes = scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(rev.getId());
        AdStatusEngine.EvalResult eval =
                engine.evaluate(rev, scopes, aircraft, java.time.LocalDate.now());
        if (!eval.applicable()) {
            throw new BizException("飞机 " + aircraft.getTailNo() + "（" + aircraft.getModel()
                    + "）不在该指令的适用范围内，不能登记落实");
        }
        if (!eval.pendingReasons().isEmpty()) {
            throw new BizException("当前处于待核实状态，核实通过前不能登记落实：" + String.join("；", eval.pendingReasons()));
        }

        AdCompliance row = complianceRepository
                .findByRevisionIdAndAircraftIdForUpdate(revisionId, aircraftId).orElse(null);
        if (row == null) {
            row = new AdCompliance();
            row.setRevisionId(revisionId);
            row.setAircraftId(aircraftId);
            row.setApplicable(1);
            row.setFactVersion(0L);
            row.setComputedAt(LocalDateTime.now());
            row = complianceRepository.save(row);
        }
        if (AdComplianceStatus.COMPLIED.equals(row.getStatus())) {
            throw new BizException("该机对该修订版已有当前落实结果（同一架飞机同一修订版只能有一个）；"
                    + "如需补充，请使用“追加工卡 / 追加证据”");
        }
        if (cardIds == null || cardIds.isEmpty()) {
            throw new BizException("落实必须关联至少一张属于本机且已经关闭的工卡");
        }

        List<String> linkedNos = new ArrayList<>();
        for (Long cardId : cardIds) {
            WorkCard card = workCardRepository.findById(cardId)
                    .orElseThrow(() -> new BizException("工卡不存在：id=" + cardId));
            if (!card.getAircraftId().equals(aircraftId)) {
                throw new BizException("工卡 " + card.getCardNo() + " 不属于飞机 " + aircraft.getTailNo()
                        + "，不能作为本机落实依据");
            }
            if (!"CLOSED".equals(card.getStatus())) {
                throw new BizException("工卡 " + card.getCardNo() + " 当前为「"
                        + com.aircraft.mro.domain.MroDict.cardStatusName(card.getStatus())
                        + "」，只有已关闭工卡可以作为落实依据");
            }
            if (cardLinkRepository.existsByComplianceIdAndCardId(row.getId(), cardId)) {
                throw new BizException("工卡 " + card.getCardNo() + " 已关联到本次落实，重复关联不会生成第二条记录");
            }
            AdComplianceCard link = new AdComplianceCard();
            link.setComplianceId(row.getId());
            link.setCardId(card.getId());
            link.setCardNo(card.getCardNo());
            link.setLinkedAt(LocalDateTime.now());
            link.setLinkedBy(operator);
            cardLinkRepository.save(link);
            linkedNos.add(card.getCardNo());
            recordEvent(rev, aircraftId, "CARD_LINK", null, null, operator,
                    "落实关联已关闭工卡 " + card.getCardNo() + "（" + card.getTitle() + "）", null);
        }

        String refNo = evidenceRefNo == null ? null : evidenceRefNo.trim();
        boolean hasFile = uploadFile != null && !uploadFile.isEmpty();
        boolean hasRef = refNo != null && !refNo.isEmpty();
        if (!hasFile && !hasRef) {
            throw new BizException("落实必须上传文件或登记可追溯的证据编号（签署页/归档号/链接等）");
        }
        LocalDateTime now = LocalDateTime.now();
        if (hasRef) {
            saveEvidence(row, AdEvidence.REGISTER, refNo, null, null, evidenceNote, operator, now, rev, aircraftId);
        }
        if (hasFile) {
            String[] stored = storeFile(uploadFile);
            saveEvidence(row, AdEvidence.UPLOAD, null, stored[1], stored[0], evidenceNote, operator, now, rev, aircraftId);
        }

        String oldStatus = row.getStatus();
        row.setStatus(AdComplianceStatus.COMPLIED);
        row.setApplicable(1);
        row.setGoverningKind(null);
        row.setPendingReason(null);
        row.setCompiledAt(now);
        row.setCompiledBy(operator);
        row.setFactVersion(row.getFactVersion() + 1);
        row.setFactSig(signature(AdComplianceStatus.COMPLIED, eval, List.of(), row));
        row.setComputedAt(now);
        row.setUpdatedAt(now);
        complianceRepository.save(row);
        recordEvent(rev, aircraftId, "COMPLY", oldStatus, AdComplianceStatus.COMPLIED, operator,
                "登记落实：关联工卡 " + String.join("、", linkedNos)
                        + "；证据：" + (hasRef ? "登记 " + refNo : "") + (hasRef && hasFile ? "，" : "")
                        + (hasFile ? "上传 " + uploadFile.getOriginalFilename() : ""), null);
        return assembler.complianceView(row);
    }

    /** 追加关联一张同机已关闭工卡（重复关联被唯一约束与预检挡住）。 */
    @Transactional
    public AdViews.ComplianceView linkCard(Long complianceId, Long cardId, String operator) {
        AdCompliance row = requireCompliance(complianceId);
        requireCompiled(row);
        WorkCard card = workCardRepository.findById(cardId)
                .orElseThrow(() -> new BizException("工卡不存在：id=" + cardId));
        if (!card.getAircraftId().equals(row.getAircraftId())) {
            throw new BizException("工卡 " + card.getCardNo() + " 不属于本落实记录对应的飞机");
        }
        if (!"CLOSED".equals(card.getStatus())) {
            throw new BizException("只有已关闭工卡可以关联，工卡 " + card.getCardNo() + " 当前为「"
                    + com.aircraft.mro.domain.MroDict.cardStatusName(card.getStatus()) + "」");
        }
        if (cardLinkRepository.existsByComplianceIdAndCardId(row.getId(), cardId)) {
            throw new BizException("工卡 " + card.getCardNo() + " 已关联，不能重复生成关联记录");
        }
        AdComplianceCard link = new AdComplianceCard();
        link.setComplianceId(row.getId());
        link.setCardId(card.getId());
        link.setCardNo(card.getCardNo());
        link.setLinkedAt(LocalDateTime.now());
        link.setLinkedBy(operator);
        cardLinkRepository.save(link);
        AdRevision rev = revisionRepository.findById(row.getRevisionId()).orElseThrow();
        recordEvent(rev, row.getAircraftId(), "CARD_LINK", null, null, operator,
                "追加关联已关闭工卡 " + card.getCardNo(), null);
        return assembler.complianceView(row);
    }

    /** 追加登记一条外部凭据证据。 */
    @Transactional
    public AdViews.ComplianceView addEvidence(Long complianceId, String refNo, String note, String operator) {
        AdCompliance row = requireCompliance(complianceId);
        requireCompiled(row);
        AdRevision rev = revisionRepository.findById(row.getRevisionId()).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        saveEvidence(row, AdEvidence.REGISTER, refNo.trim(), null, null, note, operator, now, rev,
                row.getAircraftId());
        return assembler.complianceView(row);
    }

    /** 追加上传一条文件证据。 */
    @Transactional
    public AdViews.ComplianceView uploadEvidence(Long complianceId, MultipartFile file,
                                                 String note, String operator) {
        AdCompliance row = requireCompliance(complianceId);
        requireCompiled(row);
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        AdRevision rev = revisionRepository.findById(row.getRevisionId()).orElseThrow();
        String[] stored = storeFile(file);
        saveEvidence(row, AdEvidence.UPLOAD, null, stored[1], stored[0], note, operator,
                LocalDateTime.now(), rev, row.getAircraftId());
        return assembler.complianceView(row);
    }

    /** 标记证据缺失（记录保留不删），原已落实自动转待核实。 */
    @Transactional
    public AdViews.ComplianceView markEvidenceMissing(Long evidenceId, String reason, String operator) {
        AdEvidence evidence = evidenceRepository.findById(evidenceId)
                .orElseThrow(() -> new BizException("证据不存在：id=" + evidenceId));
        evidence.setMissing(1);
        evidenceRepository.save(evidence);
        AdCompliance row = requireCompliance(evidence.getComplianceId());
        AdRevision rev = revisionRepository.findById(row.getRevisionId()).orElseThrow();
        recordEvent(rev, row.getAircraftId(), "EVIDENCE_MISSING", null, null, operator,
                "证据 " + evidenceName(evidence) + " 被标记为缺失"
                        + (reason == null || reason.isBlank() ? "" : "，原因：" + reason.trim()), null);
        // 立即按最新事实重算本行：已落实 → 待核实（历史证据与关联仍保留）
        self.recomputeJoined(rev.getId(), row.getAircraftId(), operator,
                "证据缺失，重新核实落实有效性", null);
        return assembler.complianceView(requireCompliance(row.getId()));
    }

    /** 人工核实：待核实行按最新事实立即重算；事实已补齐则恢复已落实/期限状态。 */
    @Transactional
    public AdViews.ComplianceView reverify(Long complianceId, String operator, String note) {
        AdCompliance row = requireCompliance(complianceId);
        if (!AdComplianceStatus.PENDING_VERIFY.equals(row.getStatus())) {
            throw new BizException("只有待核实状态可以发起人工核实，当前为「"
                    + AdComplianceStatus.name(row.getStatus()) + "」");
        }
        AdRevision rev = revisionRepository.findById(row.getRevisionId()).orElseThrow();
        if (!"ACTIVE".equals(rev.getStatus())) {
            throw new BizException("该结果属于已替代的历史版本 R" + rev.getRevisionNo() + "，结果冻结不可重算");
        }
        boolean hadComply = !cardLinkRepository.findByComplianceId(row.getId()).isEmpty();
        var outcome = self.recomputeJoined(rev.getId(), row.getAircraftId(), operator,
                "人工核实：" + (note == null || note.isBlank() ? "按最新事实重新判定" : note.trim()), null);
        if (hadComply && AdComplianceStatus.COMPLIED.equals(outcome.status())) {
            recordEvent(rev, row.getAircraftId(), "REVERIFY",
                    AdComplianceStatus.PENDING_VERIFY, AdComplianceStatus.COMPLIED, operator,
                    "人工核实通过：关联工卡与证据重新满足要求，恢复已落实", null);
        }
        return assembler.complianceView(requireCompliance(row.getId()));
    }

    // ============================== 证据落盘 ==============================

    /** @return [storedRelativePath, fileName] */
    private String[] storeFile(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "evidence.dat" : file.getOriginalFilename();
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0 && dot < original.length() - 1) {
            ext = original.substring(dot);
        }
        String relative = java.time.LocalDate.now() + "/" + UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = evidenceDir.resolve(relative).normalize();
        if (!target.startsWith(evidenceDir)) {
            throw new BizException("非法的证据文件路径");
        }
        try {
            Files.createDirectories(target.getParent());
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("证据文件保存失败：" + e.getMessage());
        }
        return new String[] {relative, original};
    }

    public Path resolveEvidence(AdEvidence evidence) {
        Path p = evidenceDir.resolve(evidence.getStoredPath()).normalize();
        if (!p.startsWith(evidenceDir) || !Files.isRegularFile(p)) {
            throw new BizException("证据文件在服务端不存在或已被移动：" + evidence.getFileName());
        }
        return p;
    }

    private void saveEvidence(AdCompliance row, String type, String refNo, String fileName,
                              String storedPath, String note, String operator, LocalDateTime now,
                              AdRevision rev, Long aircraftId) {
        AdEvidence evidence = new AdEvidence();
        evidence.setComplianceId(row.getId());
        evidence.setEvType(type);
        evidence.setRefNo(refNo);
        evidence.setFileName(fileName);
        evidence.setStoredPath(storedPath);
        evidence.setNote(note == null || note.isBlank() ? null : note.trim());
        evidence.setMissing(0);
        evidence.setUploadedBy(operator);
        evidence.setCreatedAt(now);
        evidenceRepository.save(evidence);
        recordEvent(rev, aircraftId, "EVIDENCE_ADD", null, null, operator,
                "追加落实证据：" + evidenceName(evidence), null);
    }

    private String evidenceName(AdEvidence e) {
        if (AdEvidence.UPLOAD.equals(e.getEvType())) {
            return "文件「" + e.getFileName() + "」";
        }
        return "凭据编号 " + e.getRefNo();
    }

    // ============================== 小工具 ==============================

    private List<AdRevision> activeRevisions() {
        List<AdRevision> all = revisionRepository.findAllByOrderByEffectiveDateDescIdAsc();
        List<AdRevision> active = new ArrayList<>();
        for (AdRevision r : all) {
            if ("ACTIVE".equals(r.getStatus())) {
                active.add(r);
            }
        }
        return active;
    }

    private AdCompliance requireCompliance(Long id) {
        return complianceRepository.findById(id)
                .orElseThrow(() -> new BizException("落实记录不存在：id=" + id));
    }

    private void requireCompiled(AdCompliance row) {
        if (!AdComplianceStatus.COMPLIED.equals(row.getStatus())) {
            throw new BizException("只有已落实的记录可以追加工卡/证据，当前为「"
                    + AdComplianceStatus.name(row.getStatus()) + "」");
        }
    }

    private void recordEvent(AdRevision rev, Long aircraftId, String type,
                             String from, String to, String operator, String detail, Long batchJobId) {
        AdEvent event = new AdEvent();
        event.setDirectiveId(rev.getDirectiveId());
        event.setRevisionId(rev.getId());
        event.setAircraftId(aircraftId);
        event.setEventType(type);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setOperator(operator == null || operator.isBlank() ? "SYSTEM" : operator);
        event.setDetail(detail);
        event.setBatchJobId(batchJobId);
        event.setEventAt(LocalDateTime.now());
        eventRepository.save(event);
    }

    /** 判定输入签名：状态/余量/原因/工卡与证据事实，任一变化即认为出现了新事实。 */
    private String signature(String status, AdStatusEngine.EvalResult eval,
                             List<String> pendingReasons, AdCompliance row) {
        StringBuilder sb = new StringBuilder();
        sb.append(status).append('|')
                .append(eval.governingKind()).append('|')
                .append(eval.daysLeft()).append('|')
                .append(eval.fhLeft()).append('|')
                .append(eval.cyclesLeft()).append('|')
                .append(eval.applicable()).append('|')
                .append(String.join(";", pendingReasons)).append('|');
        for (AdComplianceCard link : cardLinkRepository.findByComplianceId(row.getId())) {
            WorkCard card = workCardRepository.findById(link.getCardId()).orElse(null);
            sb.append("C:").append(link.getCardId())
                    .append('@').append(card == null ? "GONE" : card.getAircraftId())
                    .append('#').append(card == null ? "GONE" : card.getStatus()).append(';');
        }
        for (AdEvidence ev : evidenceRepository.findByComplianceIdOrderByCreatedAtAsc(row.getId())) {
            sb.append("E:").append(ev.getId()).append('@').append(ev.getMissing()).append(';');
        }
        return Integer.toHexString(sb.toString().hashCode());
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }

    /** pending_reason 列为 VARCHAR(512)，多原因拼接后按列宽截断。 */
    private String clampReason(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
