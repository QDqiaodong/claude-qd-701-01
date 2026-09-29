package com.aircraft.mro.ad.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.aircraft.mro.ad.domain.AdCompliance;
import com.aircraft.mro.ad.domain.AdComplianceCard;
import com.aircraft.mro.ad.domain.AdDirective;
import com.aircraft.mro.ad.domain.AdEvidence;
import com.aircraft.mro.ad.domain.AdEvent;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.repository.AdComplianceCardRepository;
import com.aircraft.mro.ad.repository.AdComplianceRepository;
import com.aircraft.mro.ad.repository.AdDirectiveRepository;
import com.aircraft.mro.ad.repository.AdEvidenceRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.domain.MroDict;
import com.aircraft.mro.domain.WorkCard;
import com.aircraft.mro.repository.AircraftRepository;
import com.aircraft.mro.repository.WorkCardRepository;

/**
 * 把领域实体组装成前端视图。状态中文名、工卡当前事实（是否同机、是否仍关闭）
 * 全部在服务端拼好，前端不做任何期限计算。
 */
@Component
public class AdViewAssembler {

    private final AdDirectiveRepository directiveRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdScopeRepository scopeRepository;
    private final AdComplianceRepository complianceRepository;
    private final AdComplianceCardRepository cardLinkRepository;
    private final AdEvidenceRepository evidenceRepository;
    private final AdEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final WorkCardRepository workCardRepository;

    public AdViewAssembler(AdDirectiveRepository directiveRepository,
                           AdRevisionRepository revisionRepository,
                           AdScopeRepository scopeRepository,
                           AdComplianceRepository complianceRepository,
                           AdComplianceCardRepository cardLinkRepository,
                           AdEvidenceRepository evidenceRepository,
                           AdEventRepository eventRepository,
                           AircraftRepository aircraftRepository,
                           WorkCardRepository workCardRepository) {
        this.directiveRepository = directiveRepository;
        this.revisionRepository = revisionRepository;
        this.scopeRepository = scopeRepository;
        this.complianceRepository = complianceRepository;
        this.cardLinkRepository = cardLinkRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.workCardRepository = workCardRepository;
    }

    // ---------------- 基础索引 ----------------

    private Map<Long, Aircraft> aircraftMap() {
        Map<Long, Aircraft> map = new HashMap<>();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            map.put(a.getId(), a);
        }
        return map;
    }

    private Map<Long, AdDirective> directiveMap() {
        Map<Long, AdDirective> map = new HashMap<>();
        for (AdDirective d : directiveRepository.findAllByOrderByAdNoAsc()) {
            map.put(d.getId(), d);
        }
        return map;
    }

    private Map<Long, AdRevision> revisionMap() {
        Map<Long, AdRevision> map = new HashMap<>();
        for (AdRevision r : revisionRepository.findAllByOrderByEffectiveDateDescIdAsc()) {
            map.put(r.getId(), r);
        }
        return map;
    }

    // ---------------- 指令目录 ----------------

    public List<AdViews.DirectiveView> directives() {
        Map<Long, AdDirective> directives = directiveMap();
        Map<Long, List<AdRevision>> revByDirective = new HashMap<>();
        for (AdRevision rev : revisionRepository.findAllByOrderByEffectiveDateDescIdAsc()) {
            revByDirective.computeIfAbsent(rev.getDirectiveId(), k -> new ArrayList<>()).add(rev);
        }
        List<AdCompliance> allRows = complianceRepository.findAll();
        Map<Long, long[]> stats = new HashMap<>(); // revId -> [overdue, dueSoon, pending, complied, applicable]
        for (AdCompliance c : allRows) {
            long[] arr = stats.computeIfAbsent(c.getRevisionId(), k -> new long[5]);
            if (c.getApplicable() != null && c.getApplicable() == 1) {
                arr[4]++;
            }
            switch (c.getStatus()) {
                case AdComplianceStatus.OVERDUE -> arr[0]++;
                case AdComplianceStatus.DUE_SOON -> arr[1]++;
                case AdComplianceStatus.PENDING_VERIFY -> arr[2]++;
                case AdComplianceStatus.COMPLIED -> arr[3]++;
                default -> {
                }
            }
        }
        List<AdViews.DirectiveView> list = new ArrayList<>();
        for (AdDirective d : directiveRepository.findAllByOrderByAdNoAsc()) {
            List<AdRevision> revs = revByDirective.getOrDefault(d.getId(), List.of());
            AdRevision current = d.getCurrentRevId() == null ? null
                    : revs.stream().filter(r -> r.getId().equals(d.getCurrentRevId())).findFirst().orElse(null);
            long[] s = current == null ? new long[5] : stats.getOrDefault(current.getId(), new long[5]);
            list.add(new AdViews.DirectiveView(
                    d.getId(), d.getAdNo(), d.getTitle(), d.getIssuer(), d.getCurrentRevId(),
                    current == null ? null : current.getRevisionNo(),
                    current == null ? null : current.getEffectiveDate(),
                    revs.size(), s[0], s[1], s[2], s[3], s[4], d.getCreatedAt(), d.getUpdatedAt()));
        }
        return list;
    }

    // ---------------- 修订版 ----------------

    public List<AdViews.RevisionView> revisions(Long directiveId) {
        Map<Long, AdDirective> directives = directiveMap();
        List<AdRevision> revs = directiveId == null
                ? revisionRepository.findAllByOrderByEffectiveDateDescIdAsc()
                : revisionRepository.findByDirectiveIdOrderByRevisionNoAsc(directiveId);
        List<AdViews.RevisionView> list = new ArrayList<>();
        for (AdRevision rev : revs) {
            AdDirective d = directives.get(rev.getDirectiveId());
            list.add(toRevisionView(rev, d));
        }
        return list;
    }

    public AdViews.RevisionView toRevisionView(AdRevision rev, AdDirective directive) {
        AdDirective d = directive != null ? directive
                : directiveRepository.findById(rev.getDirectiveId()).orElse(null);
        List<AdViews.ScopeView> scopes = new ArrayList<>();
        for (AdScope s : scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(rev.getId())) {
            scopes.add(scopeView(s));
        }
        return new AdViews.RevisionView(
                rev.getId(), rev.getDirectiveId(), d == null ? null : d.getAdNo(),
                d == null ? null : d.getTitle(), d == null ? null : d.getIssuer(),
                rev.getRevisionNo(), rev.getStatus(), revisionStatusName(rev.getStatus()),
                rev.getEffectiveDate(), rev.getDueDate(), rev.getDueFh(), rev.getDueCycles(),
                rev.getWarnDays(), rev.getWarnFh(), rev.getWarnCycles(),
                rev.getConfigIssue(), rev.getDemoFailTails(), scopes, rev.getCreatedAt(), rev.getUpdatedAt());
    }

    public AdViews.ScopeView scopeView(AdScope s) {
        String lower = blankToNull(s.getTailLower());
        String upper = blankToNull(s.getTailUpper());
        String coverage;
        if (lower == null && upper == null) {
            coverage = MroDict.modelNameOrSelf(s.getModel()) + " 全部机号";
        } else if (lower != null && upper != null) {
            coverage = MroDict.modelNameOrSelf(s.getModel()) + " " + lower + " ~ " + upper;
        } else {
            coverage = MroDict.modelNameOrSelf(s.getModel()) + " "
                    + (lower != null ? "≥ " + lower : "≤ " + upper) + "（边界不完整）";
        }
        return new AdViews.ScopeView(s.getId(), s.getModel(),
                MroDict.modelNameOrSelf(s.getModel()), lower, upper, coverage);
    }

    // ---------------- 落实清单 ----------------

    public List<AdViews.ComplianceView> complianceRows(List<AdCompliance> rows) {
        Map<Long, Aircraft> aircraft = aircraftMap();
        Map<Long, AdDirective> directives = directiveMap();
        Map<Long, AdRevision> revisions = revisionMap();
        List<AdViews.ComplianceView> list = new ArrayList<>();
        for (AdCompliance row : rows) {
            list.add(toComplianceView(row, aircraft, directives, revisions));
        }
        return list;
    }

    public AdViews.ComplianceView complianceView(AdCompliance row) {
        return toComplianceView(row, aircraftMap(), directiveMap(), revisionMap());
    }

    private AdViews.ComplianceView toComplianceView(AdCompliance row,
                                                    Map<Long, Aircraft> aircraftMap,
                                                    Map<Long, AdDirective> directives,
                                                    Map<Long, AdRevision> revisions) {
        Aircraft a = aircraftMap.get(row.getAircraftId());
        AdRevision rev = revisions.get(row.getRevisionId());
        AdDirective d = rev == null ? null : directives.get(rev.getDirectiveId());
        List<AdViews.ComplianceCardView> cards = new ArrayList<>();
        for (AdComplianceCard link : cardLinkRepository.findByComplianceId(row.getId())) {
            WorkCard card = workCardRepository.findById(link.getCardId()).orElse(null);
            boolean sameAircraft = card != null && card.getAircraftId().equals(row.getAircraftId());
            boolean closed = card != null && "CLOSED".equals(card.getStatus());
            String invalidReason = null;
            if (card == null) {
                invalidReason = "工卡已不存在";
            } else if (!sameAircraft) {
                Aircraft other = aircraftMap.get(card.getAircraftId());
                invalidReason = "已改挂到 " + (other == null ? "其他飞机" : other.getTailNo());
            } else if ("CANCELED".equals(card.getStatus())) {
                invalidReason = "工卡已取消";
            } else if (!closed) {
                invalidReason = "工卡已重新打开（" + MroDict.cardStatusName(card.getStatus()) + "）";
            }
            cards.add(new AdViews.ComplianceCardView(
                    link.getId(), link.getCardId(), link.getCardNo(),
                    card == null ? null : card.getTitle(),
                    card == null ? null : card.getAircraftId(),
                    card == null ? null : aircraftMap.get(card.getAircraftId()) == null
                            ? null : aircraftMap.get(card.getAircraftId()).getTailNo(),
                    card == null ? null : card.getStatus(),
                    card == null ? null : MroDict.cardStatusName(card.getStatus()),
                    sameAircraft, closed, sameAircraft && closed, invalidReason,
                    link.getLinkedAt(), link.getLinkedBy()));
        }
        List<AdViews.EvidenceView> evidences = new ArrayList<>();
        for (AdEvidence e : evidenceRepository.findByComplianceIdOrderByCreatedAtAsc(row.getId())) {
            evidences.add(new AdViews.EvidenceView(
                    e.getId(), e.getEvType(), AdEvidence.UPLOAD.equals(e.getEvType()) ? "上传文件" : "登记凭据",
                    e.getRefNo(), e.getFileName(), e.getNote(),
                    e.getMissing() != null && e.getMissing() == 1, e.getUploadedBy(), e.getCreatedAt()));
        }
        boolean totalMissing = a != null && (a.getTotalFh() == null || a.getTotalCycles() == null);
        return new AdViews.ComplianceView(
                row.getId(), row.getRevisionId(),
                d == null ? null : d.getId(), d == null ? null : d.getAdNo(),
                rev == null ? null : rev.getRevisionNo(),
                rev == null ? null : rev.getStatus(),
                row.getAircraftId(),
                a == null ? "?" : a.getTailNo(),
                a == null ? null : a.getModel(),
                a == null ? null : MroDict.modelNameOrSelf(a.getModel()),
                a == null ? null : a.getAirline(),
                a == null ? null : a.getTotalFh(),
                a == null ? null : a.getTotalCycles(),
                totalMissing,
                row.getApplicable() != null && row.getApplicable() == 1,
                row.getStatus(), AdComplianceStatus.name(row.getStatus()),
                row.getGoverningKind(), governingName(row.getGoverningKind()),
                row.getDaysLeft(), row.getFhLeft(), row.getCyclesLeft(),
                row.getPendingReason(), row.getCompiledAt(), row.getCompiledBy(),
                row.getFactVersion() == null ? 0 : row.getFactVersion(),
                row.getComputedAt(), cards, evidences);
    }

    // ---------------- 事件流 ----------------

    public List<AdViews.EventView> events(List<AdEvent> events) {
        Map<Long, Aircraft> aircraft = aircraftMap();
        Map<Long, AdDirective> directives = directiveMap();
        Map<Long, AdRevision> revisions = revisionMap();
        List<AdViews.EventView> list = new ArrayList<>();
        for (AdEvent e : events) {
            Aircraft a = e.getAircraftId() == null ? null : aircraft.get(e.getAircraftId());
            AdDirective d = directives.get(e.getDirectiveId());
            AdRevision r = revisions.get(e.getRevisionId());
            list.add(new AdViews.EventView(
                    e.getId(), e.getDirectiveId(), e.getRevisionId(), e.getAircraftId(),
                    a == null ? null : a.getTailNo(),
                    d == null ? null : d.getAdNo(),
                    r == null ? null : r.getRevisionNo(),
                    e.getEventType(), eventName(e.getEventType()),
                    e.getFromStatus(), AdComplianceStatus.name(e.getFromStatus()),
                    e.getToStatus(), AdComplianceStatus.name(e.getToStatus()),
                    e.getOperator(), e.getDetail(), e.getBatchJobId(), e.getEventAt()));
        }
        return list;
    }

    public static String governingName(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "DATE" -> "日历日期";
            case "FH" -> "飞行小时";
            case "CYCLE" -> "起落次数";
            default -> kind;
        };
    }

    public static String revisionStatusName(String status) {
        return "SUPERSEDED".equals(status) ? "被替代（历史冻结）" : "ACTIVE".equals(status) ? "当前生效" : status;
    }

    /** 可选的同机已关闭工卡 */
    public List<AdViews.ClosedCardView> closedCards(Long aircraftId) {
        List<AdViews.ClosedCardView> list = new ArrayList<>();
        for (WorkCard c : workCardRepository.findAllByAircraftIdOrderByCardNoAsc(aircraftId)) {
            if (!"CLOSED".equals(c.getStatus())) {
                continue;
            }
            list.add(new AdViews.ClosedCardView(c.getId(), c.getCardNo(), c.getTitle(),
                    MroDict.checkTypeName(c.getCheckType()), c.getEndTime(), c.getExecutor(), c.getInspector()));
        }
        return list;
    }

    public static String eventName(String type) {
        return switch (type) {
            case "CREATE" -> "发布指令";
            case "REVISE" -> "发布修订版";
            case "SCOPE_CHANGE" -> "适用范围变更";
            case "THRESHOLD_CHANGE" -> "截止阈值变更";
            case "RECOMPUTE" -> "触发重算";
            case "STATUS_CHANGE" -> "状态变化";
            case "COMPLY" -> "登记落实";
            case "CARD_LINK" -> "关联工卡";
            case "CARD_UNLINK" -> "解除工卡关联";
            case "CARD_CHANGED" -> "工卡变化回待核实";
            case "EVIDENCE_ADD" -> "追加证据";
            case "EVIDENCE_MISSING" -> "证据标记缺失";
            case "REVERIFY" -> "人工核实";
            case "BATCH_START" -> "批量重算开始";
            case "BATCH_DONE" -> "批量重算完成";
            case "BATCH_FAIL" -> "批量重算失败";
            case "BATCH_RETRY" -> "失败重试";
            default -> type;
        };
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
