package com.aircraft.mro.ad.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.ad.domain.AdBatchItem;
import com.aircraft.mro.ad.domain.AdBatchJob;
import com.aircraft.mro.ad.domain.AdDirective;
import com.aircraft.mro.ad.domain.AdEvent;
import com.aircraft.mro.ad.domain.AdRevision;
import com.aircraft.mro.ad.domain.AdScope;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.repository.AdBatchItemRepository;
import com.aircraft.mro.ad.repository.AdBatchJobRepository;
import com.aircraft.mro.ad.repository.AdDirectiveRepository;
import com.aircraft.mro.ad.repository.AdEventRepository;
import com.aircraft.mro.ad.repository.AdRevisionRepository;
import com.aircraft.mro.ad.repository.AdScopeRepository;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Aircraft;
import com.aircraft.mro.repository.AircraftRepository;

/**
 * 批量重算：修订发布 / 范围变更 / 阈值变更 / 手动触发时，对“该指令覆盖的机队”逐架重算。
 *
 * <p>不占住普通查询与飞行登记的三个设计：
 * <ol>
 *   <li>任务在单线程 {@code adRecomputeExecutor} 上跑，HTTP 请求只负责建档入队并立即返回；</li>
 *   <li>每架飞机一个独立短事务（{@code REQUIRES_NEW}），一把飞机行锁只持有毫秒级；</li>
 *   <li>进度（成功/失败/进行中）逐架提交持久化，页面轮询看到的永远是服务端真实进度；
 *       单项失败只记失败原因并继续后续飞机，失败项可重试，重试不重复生成结果。</li>
 * </ol>
 */
@Service
public class AdBatchService {

    /** 【仅演示】单项处理间隔，让大批量进度在页面上肉眼可见；生产可置 0 */
    private static final long DEMO_ITEM_SLEEP_MS = 60L;

    private final AdBatchJobRepository jobRepository;
    private final AdBatchItemRepository itemRepository;
    private final AdRevisionRepository revisionRepository;
    private final AdScopeRepository scopeRepository;
    private final AdDirectiveRepository directiveRepository;
    private final AdEventRepository eventRepository;
    private final AircraftRepository aircraftRepository;
    private final AdComplianceCoordinator coordinator;
    private final AdStatusEngine engine;
    private final AdViewAssembler assembler;
    private final AdBatchService self;

    /** 同一修订版同时只允许一个进行中的批量任务（防手动狂点/编辑并发入队） */
    private final Set<Long> queuedRevisionIds = new HashSet<>();

    public AdBatchService(AdBatchJobRepository jobRepository,
                          AdBatchItemRepository itemRepository,
                          AdRevisionRepository revisionRepository,
                          AdScopeRepository scopeRepository,
                          AdDirectiveRepository directiveRepository,
                          AdEventRepository eventRepository,
                          AircraftRepository aircraftRepository,
                          AdComplianceCoordinator coordinator,
                          AdStatusEngine engine,
                          AdViewAssembler assembler,
                          @Lazy AdBatchService self) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.revisionRepository = revisionRepository;
        this.scopeRepository = scopeRepository;
        this.directiveRepository = directiveRepository;
        this.eventRepository = eventRepository;
        this.aircraftRepository = aircraftRepository;
        this.coordinator = coordinator;
        this.engine = engine;
        this.assembler = assembler;
        this.self = self;
    }

    // ============================== 入队 ==============================

    /**
     * 为一个修订版的“覆盖机队”建档并入队。自身事务保证任务头+明细原子落库，
     * 事务提交后再调度后台线程（{@link AdDirectiveService} 已在 afterCommit 调用，
     * 这里再包一层也幂等安全）。
     *
     * @param triggerType REVISION / SCOPE / THRESHOLD / MANUAL / RETRY
     */
    @Transactional
    public synchronized AdBatchJob enqueue(Long revisionId, String triggerType, String operator, String reason) {
        AdRevision rev = revisionRepository.findById(revisionId)
                .orElseThrow(() -> new BizException("适航指令修订版不存在：id=" + revisionId));
        if (!"ACTIVE".equals(rev.getStatus())) {
            throw new BizException("被替代的历史版本不再触发重算");
        }
        if (queuedRevisionIds.contains(revisionId)) {
            throw new BizException("该修订版的批量重算正在进行中，请等待当前任务结束（可在“批量重算”面板查看进度）");
        }
        for (AdBatchJob running : jobRepository.findByStatus(AdBatchJob.RUNNING)) {
            if (running.getRevisionId().equals(revisionId)) {
                throw new BizException("该修订版已有进行中的批量重算任务 #" + running.getId());
            }
        }
        AdDirective directive = directiveRepository.findById(rev.getDirectiveId())
                .orElseThrow(() -> new BizException("适航指令不存在：id=" + rev.getDirectiveId()));

        List<AdScope> scopes = scopeRepository.findByRevisionIdOrderBySeqNoAscIdAsc(rev.getId());
        List<Aircraft> fleet = new ArrayList<>();
        for (Aircraft a : aircraftRepository.findAllByOrderByTailNoAsc()) {
            // 覆盖机队 = 范围行提到过的机型（完整区间外的飞机会以 NOT_APPLICABLE 落清单，便于筛选验证）
            boolean modelMentioned = scopes.stream().anyMatch(s -> s.getModel().equalsIgnoreCase(a.getModel()));
            if (modelMentioned) {
                fleet.add(a);
            }
        }
        String scopeNote = clamp(buildScopeNote(directive.getAdNo(), rev.getRevisionNo(), scopes, fleet)
                + "；触发原因：" + (reason == null ? triggerName(triggerType) : reason), 250);

        LocalDateTime now = LocalDateTime.now();
        AdBatchJob job = new AdBatchJob();
        job.setDirectiveId(directive.getId());
        job.setRevisionId(revisionId);
        job.setTriggerType(triggerType);
        job.setOperator(operator);
        job.setScopeNote(scopeNote);
        job.setTotalCount(fleet.size());
        job.setSuccessCount(0);
        job.setFailedCount(0);
        job.setStatus(AdBatchJob.RUNNING);
        job.setStartedAt(now);
        job = jobRepository.saveAndFlush(job);

        int seq = 0;
        for (Aircraft a : fleet) {
            AdBatchItem item = new AdBatchItem();
            item.setJobId(job.getId());
            item.setAircraftId(a.getId());
            item.setStatus(AdBatchItem.PENDING);
            item.setAttempt(0);
            item.setUpdatedAt(now);
            itemRepository.save(item);
            seq++;
        }

        recordEvent(directive.getId(), revisionId, null, "BATCH_START", operator,
                "批量重算 #" + job.getId() + " 已开始：" + scopeNote + "，共 " + fleet.size()
                        + " 架，逐架短事务执行，不阻塞查询与飞行登记", job.getId(), now);
        queuedRevisionIds.add(revisionId);
        scheduleRun(job.getId());
        return job;
    }

    // ============================== 后台执行 ==============================

    private void scheduleRun(Long jobId) {
        Runnable task = () -> AdRecomputeExecutors.run(() -> {
            try {
                self.runJob(jobId);
            } finally {
                AdBatchJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    synchronized (this) {
                        queuedRevisionIds.remove(job.getRevisionId());
                    }
                }
            }
        });
        // 若当前正处在建档/重置任务的事务里，必须等事务提交后再起线程，
        // 否则后台会读不到任务或其明细（重试路径就是这种情况）。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    /**
     * 串行消费一个任务的全部待处理项。被重试任务与启动恢复复用。
     *
     * <p>刻意【不加】类级事务：每架飞机的重算（{@code coordinator.recomputeNew}）与
     * 明细/计数记账都在各自的独立短事务里提交，页面轮询能实时看到逐架进度，
     * 大批量任务也不会长时间持有任何事务或飞机行锁。
     */
    public void runJob(Long jobId) {
        AdBatchJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BizException("批量任务不存在：id=" + jobId));
        if (!AdBatchJob.RUNNING.equals(job.getStatus())) {
            // 终态任务只有在“重试”时会被重新置回 RUNNING
            return;
        }
        List<AdBatchItem> pending = itemRepository.findByJobIdAndStatusOrderByIdAsc(jobId, AdBatchItem.PENDING);
        AdRevision rev = revisionRepository.findById(job.getRevisionId()).orElse(null);
        // 【仅验收演示】首轮（任务非“失败重试”触发）对 demo_fail_tails 里的机号模拟失败一次。
        // 是否首轮以“明细 attempt==0（从未处理过）”为准，而不是计数——强制失败机在队列里可能排在
        // 已成功飞机之后；重试时这些明细 attempt 已 ≥1，绝不再注入，保证重试必然成功且不重复生成。
        Set<String> demoFailTails = new HashSet<>();
        if (rev != null && rev.getDemoFailTails() != null && !rev.getDemoFailTails().isBlank()
                && !"RETRY".equals(job.getTriggerType())) {
            demoFailTails.addAll(Arrays.asList(rev.getDemoFailTails().split(",")));
        }

        for (AdBatchItem item : pending) {
            if (DEMO_ITEM_SLEEP_MS > 0) {
                try {
                    Thread.sleep(DEMO_ITEM_SLEEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Aircraft aircraft = aircraftRepository.findById(item.getAircraftId()).orElse(null);
            String forcedFail = null;
            if (aircraft != null && item.getAttempt() == 0 && demoFailTails.contains(aircraft.getTailNo())) {
                forcedFail = "【演示注入】该架重算模拟失败（数据库瞬时不可用），请点击“重试失败项”验收部分失败后继续处理";
            }
            String failReason = null;
            if (forcedFail != null) {
                failReason = forcedFail;
            } else if (rev == null) {
                failReason = "修订版已不存在";
            } else {
                try {
                    coordinator.recomputeNew(rev.getId(), item.getAircraftId(), "BATCH#" + jobId,
                            "批量重算任务 #" + jobId, item.getId());
                } catch (Exception ex) {
                    failReason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
            }
            if (failReason != null && failReason.length() > 500) {
                failReason = failReason.substring(0, 500);
            }
            // 明细状态 + 成功/失败计数在同一个独立短事务里原子提交
            self.recordItemOutcome(item.getId(), jobId,
                    failReason == null ? AdBatchItem.SUCCESS : AdBatchItem.FAILED, failReason);
        }
        self.finalizeJob(jobId);
    }

    /** 一架飞机一个独立短事务：明细结果 + 任务计数一起提交，互不影响其它飞机。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordItemOutcome(Long itemId, Long jobId, String status, String failReason) {
        itemRepository.markResult(itemId, status, failReason, LocalDateTime.now());
        if (AdBatchItem.SUCCESS.equals(status)) {
            jobRepository.markProgress(jobId, 1, 0);
        } else {
            jobRepository.markProgress(jobId, 0, 1);
        }
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void finalizeJob(Long jobId) {
        AdBatchJob latest = jobRepository.findByIdForUpdate(jobId).orElseThrow();
        List<AdBatchItem> remaining = itemRepository.findByJobIdAndStatusOrderByIdAsc(jobId, AdBatchItem.PENDING);
        List<AdBatchItem> failedItems = itemRepository.findByJobIdAndStatusOrderByIdAsc(jobId, AdBatchItem.FAILED);
        LocalDateTime now = LocalDateTime.now();
        String status;
        String lastError = null;
        if (!remaining.isEmpty()) {
            // 单线程消费完本批 PENDING 后理论上不会剩；防御性保留 RUNNING
            status = AdBatchJob.RUNNING;
        } else if (!failedItems.isEmpty()) {
            status = AdBatchJob.PARTIAL_FAILED;
            lastError = failedItems.size() + " 架失败，最近一例：" + failedItems.get(failedItems.size() - 1).getFailReason();
        } else {
            status = AdBatchJob.DONE;
        }
        jobRepository.markFinished(jobId, status, lastError, AdBatchJob.RUNNING.equals(status) ? null : now);
        if (!AdBatchJob.RUNNING.equals(status)) {
            String eventType = AdBatchJob.DONE.equals(status) ? "BATCH_DONE" : "BATCH_FAIL";
            recordEvent(latest.getDirectiveId(), latest.getRevisionId(), null, eventType, latest.getOperator(),
                    "批量重算 #" + jobId + " 结束：共 " + latest.getTotalCount() + " 架，成功 "
                            + latest.getSuccessCount() + " 架，失败 " + latest.getFailedCount() + " 架"
                            + (AdBatchJob.PARTIAL_FAILED.equals(status) ? "（失败项可重试，重试不会重复生成结果）" : ""),
                    jobId, now);
        }
    }

    // ============================== 失败重试 ==============================

    /**
     * 把失败项重新置为待处理并恢复任务，逐项重跑。已成功的飞机不会被重放，
     * 因此不会重复生成结果；重试成功后计数按最新值收敛。
     */
    @Transactional
    public AdViews.BatchJobView retryFailed(Long jobId, String operator) {
        AdBatchJob job = jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() -> new BizException("批量任务不存在：id=" + jobId));
        if (AdBatchJob.RUNNING.equals(job.getStatus())) {
            throw new BizException("任务仍在进行中，请等待其结束");
        }
        List<AdBatchItem> failedItems = itemRepository.findByJobIdAndStatusOrderByIdAsc(jobId, AdBatchItem.FAILED);
        if (failedItems.isEmpty()) {
            throw new BizException("该任务没有失败项需要重试");
        }
        LocalDateTime now = LocalDateTime.now();
        int wasFailed = failedItems.size();
        for (AdBatchItem item : failedItems) {
            item.setStatus(AdBatchItem.PENDING);
            item.setFailReason(null);
            item.setUpdatedAt(now);
            itemRepository.save(item);
        }
        // 失败计数清零后逐架重新累计；已成功架次保留不重放，所以不会重复生成结果
        job.setStatus(AdBatchJob.RUNNING);
        job.setFailedCount(0);
        job.setLastError(null);
        job.setFinishedAt(null);
        job.setTriggerType("RETRY");
        if (operator != null && !operator.isBlank()) {
            job.setOperator(operator);
        }
        jobRepository.saveAndFlush(job);
        recordEvent(job.getDirectiveId(), job.getRevisionId(), null, "BATCH_RETRY",
                operator == null ? job.getOperator() : operator,
                "重试批量重算 #" + jobId + " 的 " + wasFailed + " 个失败项（已成功的 "
                        + job.getSuccessCount() + " 架不重放，不重复生成结果）", jobId, now);
        synchronized (this) {
            queuedRevisionIds.add(job.getRevisionId());
        }
        scheduleRun(jobId);
        return jobView(jobId);
    }

    // ============================== 读 ==============================

    @Transactional
    public List<AdViews.BatchJobView> jobs(Long revisionId) {
        List<AdBatchJob> jobs = revisionId == null
                ? jobRepository.findAllByOrderByStartedAtDescIdDesc()
                : jobRepository.findByRevisionIdOrderByStartedAtDescIdDesc(revisionId);
        List<AdViews.BatchJobView> list = new ArrayList<>();
        for (AdBatchJob job : jobs) {
            list.add(jobView(job));
        }
        return list;
    }

    @Transactional
    public AdViews.BatchJobView jobView(Long jobId) {
        AdBatchJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new BizException("批量任务不存在：id=" + jobId));
        return jobView(job);
    }

    private AdViews.BatchJobView jobView(AdBatchJob job) {
        AdDirective directive = directiveRepository.findById(job.getDirectiveId()).orElse(null);
        AdRevision rev = revisionRepository.findById(job.getRevisionId()).orElse(null);
        List<AdViews.BatchItemView> items = new ArrayList<>();
        int pending = 0;
        for (AdBatchItem item : itemRepository.findByJobIdOrderByIdAsc(job.getId())) {
            if (AdBatchItem.PENDING.equals(item.getStatus())) {
                pending++;
            }
            Aircraft a = aircraftRepository.findById(item.getAircraftId()).orElse(null);
            items.add(new AdViews.BatchItemView(
                    item.getId(), item.getAircraftId(), a == null ? "?" : a.getTailNo(),
                    a == null ? null : a.getModel(),
                    item.getStatus(), batchItemStatusName(item.getStatus()),
                    item.getAttempt(), item.getFailReason(), item.getUpdatedAt()));
        }
        return new AdViews.BatchJobView(
                job.getId(), job.getDirectiveId(), directive == null ? null : directive.getAdNo(),
                job.getRevisionId(), rev == null ? null : rev.getRevisionNo(),
                job.getTriggerType(), triggerName(job.getTriggerType()), job.getOperator(),
                job.getScopeNote(), job.getTotalCount(), job.getSuccessCount(), job.getFailedCount(), pending,
                job.getStatus(), batchJobStatusName(job.getStatus()), job.getLastError(),
                job.getStartedAt(), job.getFinishedAt(), items);
    }

    /** 启动恢复：异常退出遗留的 RUNNING 任务，PENDING 继续跑，已中断处收尾为部分失败。 */
    @Transactional
    public void recoverOnStartup() {
        for (AdBatchJob job : jobRepository.findByStatus(AdBatchJob.RUNNING)) {
            List<AdBatchItem> pending = itemRepository.findByJobIdAndStatusOrderByIdAsc(job.getId(), AdBatchItem.PENDING);
            if (pending.isEmpty()) {
                jobRepository.markFinished(job.getId(),
                        job.getFailedCount() > 0 ? AdBatchJob.PARTIAL_FAILED : AdBatchJob.DONE,
                        job.getLastError(), LocalDateTime.now());
            } else {
                // 继续执行未完成项
                queuedRevisionIds.add(job.getRevisionId());
                scheduleRun(job.getId());
            }
        }
    }

    // ---------------- 小工具 ----------------

    private String buildScopeNote(String adNo, String revNo, List<AdScope> scopes, List<Aircraft> fleet) {        List<String> parts = new ArrayList<>();
        for (AdScope s : scopes) {
            parts.add(com.aircraft.mro.domain.MroDict.modelNameOrSelf(s.getModel())
                    + (s.getTailLower() == null && s.getTailUpper() == null
                            ? "(全机号)"
                            : "(" + (s.getTailLower() == null ? "?" : s.getTailLower())
                                    + "~" + (s.getTailUpper() == null ? "?" : s.getTailUpper()) + ")"));
        }
        return adNo + " R" + revNo + " 覆盖机队：" + String.join("、", parts) + "，共 " + fleet.size() + " 架";
    }

    /** 范围说明落库前按列宽截断，避免严格 SQL 模式下超长报错。 */
    private String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void recordEvent(Long directiveId, Long revisionId, Long aircraftId, String type,
                             String operator, String detail, Long jobId, LocalDateTime at) {
        AdEvent event = new AdEvent();
        event.setDirectiveId(directiveId);
        event.setRevisionId(revisionId);
        event.setAircraftId(aircraftId);
        event.setEventType(type);
        event.setOperator(operator == null || operator.isBlank() ? "SYSTEM" : operator);
        event.setDetail(detail);
        event.setBatchJobId(jobId);
        event.setEventAt(at);
        eventRepository.save(event);
    }

    /** 非事务收尾路径（runJob）写事件：独立短事务提交。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void recordEventCommitted(Long directiveId, Long revisionId, Long aircraftId, String type,
                                     String operator, String detail, Long jobId) {
        recordEvent(directiveId, revisionId, aircraftId, type, operator, detail, jobId, LocalDateTime.now());
    }

    public static String triggerName(String type) {
        return switch (type) {
            case "REVISION" -> "发布修订版";
            case "SCOPE" -> "适用范围变更";
            case "THRESHOLD" -> "截止阈值变更";
            case "MANUAL" -> "手动重算";
            case "RETRY" -> "失败重试";
            default -> type;
        };
    }

    public static String batchJobStatusName(String status) {
        return switch (status) {
            case AdBatchJob.RUNNING -> "进行中";
            case AdBatchJob.PARTIAL_FAILED -> "部分失败";
            case AdBatchJob.DONE -> "全部成功";
            default -> status;
        };
    }

    public static String batchItemStatusName(String status) {
        return switch (status) {
            case AdBatchItem.PENDING -> "待处理";
            case AdBatchItem.SUCCESS -> "成功";
            case AdBatchItem.FAILED -> "失败";
            default -> status;
        };
    }
}
