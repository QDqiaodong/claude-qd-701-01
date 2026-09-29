package com.aircraft.mro.ad.controller;

import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aircraft.mro.ad.domain.AdEvidence;
import com.aircraft.mro.ad.dto.AdDtos;
import com.aircraft.mro.ad.dto.AdViews;
import com.aircraft.mro.ad.service.AdBatchService;
import com.aircraft.mro.ad.service.AdComplianceCoordinator;
import com.aircraft.mro.ad.service.AdDirectiveService;
import com.aircraft.mro.ad.service.AdQueryService;
import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.common.BizException;

import jakarta.validation.Valid;

/**
 * 模块七：适航指令落实追踪。所有期限/状态/进度判定都在服务端，前端只展示。
 */
@RestController
@RequestMapping("/api/ad")
public class AdController {

    private final AdDirectiveService directiveService;
    private final AdQueryService queryService;
    private final AdComplianceCoordinator coordinator;
    private final AdBatchService batchService;

    public AdController(AdDirectiveService directiveService,
                        AdQueryService queryService,
                        AdComplianceCoordinator coordinator,
                        AdBatchService batchService) {
        this.directiveService = directiveService;
        this.queryService = queryService;
        this.coordinator = coordinator;
        this.batchService = batchService;
    }

    @GetMapping("/overview")
    public ApiResponse<AdViews.Overview> overview() {
        return ApiResponse.success(queryService.overview());
    }

    // ---------------- 指令目录 / 修订版 ----------------

    @GetMapping("/directives")
    public ApiResponse<List<AdViews.DirectiveView>> directives() {
        return ApiResponse.success(directiveService.listDirectives());
    }

    @GetMapping("/directives/{id}/revisions")
    public ApiResponse<List<AdViews.RevisionView>> revisions(@PathVariable("id") Long directiveId) {
        return ApiResponse.success(directiveService.listRevisions(directiveId));
    }

    @GetMapping("/revisions/{id}")
    public ApiResponse<AdViews.RevisionView> revision(@PathVariable("id") Long revisionId) {
        return ApiResponse.success(directiveService.revision(revisionId));
    }

    @PostMapping("/directives")
    public ApiResponse<AdViews.RevisionView> create(@Valid @RequestBody AdDtos.CreateDirectiveReq req) {
        return ApiResponse.success("适航指令已发布，覆盖机队正在后台重算", directiveService.create(req));
    }

    @PostMapping("/directives/{id}/revise")
    public ApiResponse<AdViews.RevisionView> revise(@PathVariable("id") Long directiveId,
                                                    @Valid @RequestBody AdDtos.ReviseReq req) {
        return ApiResponse.success("新修订版已发布，旧版结果已冻结保留，新版机队正在后台重算",
                directiveService.revise(directiveId, req));
    }

    @PostMapping("/revisions/{id}")
    public ApiResponse<AdViews.RevisionView> editRevision(@PathVariable("id") Long revisionId,
                                                          @Valid @RequestBody AdDtos.EditRevisionReq req) {
        return ApiResponse.success("适用范围/阈值已更新，覆盖机队正在后台重算",
                directiveService.editRevision(revisionId, req));
    }

    @PostMapping("/revisions/{id}/recompute")
    public ApiResponse<List<AdViews.BatchJobView>> recompute(@PathVariable("id") Long revisionId,
                                                             @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        directiveService.manualRecompute(revisionId, operator);
        return ApiResponse.success("批量重算任务已入队，进度在“批量重算”面板实时可见",
                batchService.jobs(revisionId));
    }

    // ---------------- 落实清单 ----------------

    @GetMapping("/compliances")
    public ApiResponse<List<AdViews.ComplianceView>> compliances(
            @RequestParam(value = "directiveId", required = false) Long directiveId,
            @RequestParam(value = "revisionId", required = false) Long revisionId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "tailNo", required = false) String tailNo,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeHistory", required = false, defaultValue = "false") boolean includeHistory) {
        return ApiResponse.success(
                queryService.complianceList(directiveId, revisionId, model, tailNo, status, includeHistory));
    }

    @GetMapping("/compliances/{id}")
    public ApiResponse<AdViews.ComplianceView> compliance(@PathVariable("id") Long id) {
        return ApiResponse.success(queryService.compliance(id));
    }

    @GetMapping("/aircraft/{aircraftId}/closed-cards")
    public ApiResponse<List<AdViews.ClosedCardView>> closedCards(@PathVariable("aircraftId") Long aircraftId) {
        return ApiResponse.success(queryService.closedCards(aircraftId));
    }

    // ---------------- 落实 / 工卡 / 证据 ----------------

    /**
     * 登记落实（multipart 表单）：revisionId、aircraftId、operator、cardIds(可多个)、
     * evidenceRefNo（登记凭据）、evidenceNote、file（上传证据，二选一至少一种）。
     */
    @PostMapping(value = "/compliances", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AdViews.ComplianceView> comply(
            @RequestParam("revisionId") Long revisionId,
            @RequestParam("aircraftId") Long aircraftId,
            @RequestParam("operator") String operator,
            @RequestParam(value = "cardIds", required = false) List<Long> cardIds,
            @RequestParam(value = "evidenceRefNo", required = false) String evidenceRefNo,
            @RequestParam(value = "evidenceNote", required = false) String evidenceNote,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success("落实结果已登记",
                coordinator.comply(revisionId, aircraftId, operator, cardIds, evidenceRefNo, evidenceNote, file));
    }

    @PostMapping("/compliances/{id}/cards")
    public ApiResponse<AdViews.ComplianceView> linkCard(@PathVariable("id") Long id,
                                                        @Valid @RequestBody AdDtos.LinkCardReq req) {
        return ApiResponse.success("已追加关联工卡", coordinator.linkCard(id, req.getCardId(), req.getOperator()));
    }

    @PostMapping("/compliances/{id}/evidence")
    public ApiResponse<AdViews.ComplianceView> addEvidence(@PathVariable("id") Long id,
                                                           @Valid @RequestBody AdDtos.AddEvidenceReq req) {
        return ApiResponse.success("证据已登记", coordinator.addEvidence(id, req.getRefNo(), req.getNote(),
                req.getOperator()));
    }

    @PostMapping(value = "/compliances/{id}/evidence-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AdViews.ComplianceView> uploadEvidence(
            @PathVariable("id") Long id,
            @RequestParam("operator") String operator,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("证据文件已上传", coordinator.uploadEvidence(id, file, note, operator));
    }

    @PostMapping("/evidence/{id}/mark-missing")
    public ApiResponse<AdViews.ComplianceView> markEvidenceMissing(
            @PathVariable("id") Long id,
            @Valid @RequestBody AdDtos.MarkEvidenceMissingReq req) {
        return ApiResponse.success("证据已标记为缺失，原落实结果转为待核实（记录保留）",
                coordinator.markEvidenceMissing(id, req.getReason(), req.getOperator()));
    }

    @PostMapping("/compliances/{id}/reverify")
    public ApiResponse<AdViews.ComplianceView> reverify(@PathVariable("id") Long id,
                                                        @Valid @RequestBody AdDtos.ReverifyReq req) {
        return ApiResponse.success("已按最新事实完成核实",
                coordinator.reverify(id, req.getOperator(), req.getNote()));
    }

    /** 证据文件下载（上传类证据）。 */
    @GetMapping("/evidence/{id}/download")
    public ResponseEntity<FileSystemResource> downloadEvidence(@PathVariable("id") Long id) {
        AdEvidence evidence = queryService.requireEvidence(id);
        if (!AdEvidence.UPLOAD.equals(evidence.getEvType()) || evidence.getStoredPath() == null) {
            throw new BizException("该证据不是上传文件，无法下载");
        }
        java.nio.file.Path path = coordinator.resolveEvidence(evidence);
        FileSystemResource resource = new FileSystemResource(path);
        String encodedName = java.net.URLEncoder.encode(
                evidence.getFileName() == null ? "evidence" : evidence.getFileName(),
                java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    // ---------------- 事件流 / 批量重算 ----------------

    @GetMapping("/events")
    public ApiResponse<List<AdViews.EventView>> events(
            @RequestParam(value = "directiveId", required = false) Long directiveId,
            @RequestParam(value = "revisionId", required = false) Long revisionId,
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(queryService.events(directiveId, revisionId, aircraftId));
    }

    @GetMapping("/batches")
    public ApiResponse<List<AdViews.BatchJobView>> batches(
            @RequestParam(value = "revisionId", required = false) Long revisionId) {
        return ApiResponse.success(batchService.jobs(revisionId));
    }

    @GetMapping("/batches/{id}")
    public ApiResponse<AdViews.BatchJobView> batch(@PathVariable("id") Long id) {
        return ApiResponse.success(batchService.jobView(id));
    }

    @PostMapping("/batches/{id}/retry")
    public ApiResponse<AdViews.BatchJobView> retryBatch(@PathVariable("id") Long id,
                                                        @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        return ApiResponse.success("失败项已重新入队重试（已成功飞机不重放）", batchService.retryFailed(id, operator));
    }
}
