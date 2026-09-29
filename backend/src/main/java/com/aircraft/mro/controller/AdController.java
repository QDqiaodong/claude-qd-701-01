package com.aircraft.mro.controller;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
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

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.common.BizException;
import com.aircraft.mro.dto.AdDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.AdService;

import jakarta.validation.Valid;

/**
 * 模块七：适航指令落实追踪。
 *
 * <p>所有适用判断、阈值余量、状态收敛都在服务端；前端只展示服务端持久化结果。
 */
@RestController
@RequestMapping("/api/ad")
public class AdController {

    private final AdService adService;

    @Value("${mro.ad.upload-dir:/data/ad-evidence}")
    private String uploadDir;

    public AdController(AdService adService) {
        this.adService = adService;
    }

    // ---------------- 总览 / 目录 / 修订版 ----------------

    @GetMapping("/overview")
    public ApiResponse<Views.AdOverview> overview() {
        return ApiResponse.success(adService.overview());
    }

    @GetMapping("/directives")
    public ApiResponse<List<Views.AdDirectiveView>> directives() {
        return ApiResponse.success(adService.directives());
    }

    @GetMapping("/directives/{id}/revisions")
    public ApiResponse<List<Views.AdRevisionView>> revisions(@PathVariable("id") Long id) {
        return ApiResponse.success(adService.revisions(id));
    }

    @PostMapping("/directives")
    public ApiResponse<Views.AdRevisionView> create(@Valid @RequestBody AdDtos.DirectiveCreateReq req) {
        return ApiResponse.success("适航指令已建档", adService.createDirective(req));
    }

    @PostMapping("/directives/{id}")
    public ApiResponse<Views.AdRevisionView> updateDirective(@PathVariable("id") Long id,
                                                             @Valid @RequestBody AdDtos.DirectiveUpdateReq req) {
        return ApiResponse.success("指令信息已更新", adService.updateDirective(id, req));
    }

    @PostMapping("/revisions/{id}/draft")
    public ApiResponse<Views.AdRevisionView> updateDraft(@PathVariable("id") Long id,
                                                         @Valid @RequestBody AdDtos.DraftUpdateReq req) {
        return ApiResponse.success("草稿内容已保存（尚未发布，不影响现行判断）", adService.updateDraft(id, req));
    }

    @PostMapping("/directives/{id}/revisions")
    public ApiResponse<Views.AdRevisionView> newRevision(@PathVariable("id") Long id,
                                                         @Valid @RequestBody AdDtos.NewRevisionReq req) {
        return ApiResponse.success("已创建新修订版草稿（旧版本将保留）", adService.newRevision(id, req));
    }

    @PostMapping("/revisions/{id}/publish")
    public ApiResponse<Views.AdRevisionView> publish(@PathVariable("id") Long id,
                                                     @Valid @RequestBody AdDtos.PublishReq req) {
        return ApiResponse.success("修订版已生效，覆盖机队将按新版本重算（旧版本结果与证据保留）",
                adService.publish(id, req));
    }

    // ---------------- 落实清单 / 详情 / 状态变化 ----------------

    /** 落实清单，可按修订版、指令、机型、机号关键字、状态筛选；includeSuperseded=true 连历史版本一起看 */
    @GetMapping("/compliance")
    public ApiResponse<List<Views.AdComplianceView>> compliance(
            @RequestParam(value = "revisionId", required = false) Long revisionId,
            @RequestParam(value = "directiveId", required = false) Long directiveId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "tailNo", required = false) String tailNo,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "includeSuperseded", required = false, defaultValue = "false")
            Boolean includeSuperseded) {
        return ApiResponse.success(adService.complianceList(revisionId, directiveId, model, tailNo, status,
                Boolean.TRUE.equals(includeSuperseded)));
    }

    @GetMapping("/compliance/{id}")
    public ApiResponse<Views.AdComplianceDetail> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(adService.complianceDetail(id));
    }

    /** 手动对单条清单发起即时复核（以服务端最新数据为准） */
    @PostMapping("/compliance/{id}/recompute")
    public ApiResponse<Views.AdComplianceView> recompute(@PathVariable("id") Long id,
                                                         @RequestBody(required = false) java.util.Map<String, String> body) {
        String operator = body == null ? "未署名" : body.getOrDefault("operator", "未署名");
        return ApiResponse.success("已按最新数据复核", adService.recomputeOne(id, operator));
    }

    /** 登记落实：关联同机已关闭工卡 + 可追溯证据 */
    @PostMapping("/compliance/{id}/comply")
    public ApiResponse<Views.AdComplianceDetail> comply(@PathVariable("id") Long id,
                                                        @Valid @RequestBody AdDtos.ComplyReq req) {
        return ApiResponse.success("落实已登记并复核成立", adService.comply(id, req));
    }

    @PostMapping("/compliance/{id}/evidence")
    public ApiResponse<Views.AdComplianceDetail> registerEvidence(@PathVariable("id") Long id,
                                                                  @Valid @RequestBody AdDtos.RegisterEvidenceReq req) {
        return ApiResponse.success("证据已登记", adService.registerEvidence(id, req));
    }

    /** 上传证据文件（可追溯：服务端落盘并登记） */
    @PostMapping("/compliance/{id}/evidence-file")
    public ApiResponse<Views.AdComplianceDetail> uploadEvidence(@PathVariable("id") Long id,
                                                                @RequestParam("file") MultipartFile file,
                                                                @RequestParam("operator") String operator) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的证据文件");
        }
        String original = file.getOriginalFilename() == null ? "evidence.bin" : file.getOriginalFilename();
        String stored = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + "-" + UUID.randomUUID().toString().substring(0, 8) + "-" + sanitize(original);
        try {
            Path dir = Paths.get(uploadDir);
            Files.createDirectories(dir);
            Path target = dir.resolve(stored);
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new BizException("证据文件保存失败：" + e.getMessage());
        }
        return ApiResponse.success("证据文件已上传并登记",
                adService.attachFileEvidence(id, sanitize(original), stored, operator));
    }

    /** 证据文件下载（按登记路径回读） */
    @GetMapping("/evidence-file/{evidenceId}/download")
    public ResponseEntity<Resource> downloadEvidence(@PathVariable("evidenceId") Long evidenceId) {
        Views.AdEvidenceView hit = adService.evidence(evidenceId);
        try {
            Path path = Paths.get(uploadDir).resolve(hit.evidenceRef());
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw new BizException("证据文件在服务器上已不可读：" + hit.fileName());
            }
            String encoded = URLEncoder.encode(
                    hit.fileName() == null ? "evidence" : hit.fileName(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(resource);
        } catch (IOException e) {
            throw new BizException("证据文件读取失败：" + e.getMessage());
        }
    }

    @PostMapping("/evidence/{id}/void")
    public ApiResponse<Views.AdComplianceDetail> voidEvidence(@PathVariable("id") Long id,
                                                              @Valid @RequestBody AdDtos.VoidEvidenceReq req) {
        return ApiResponse.success("证据已作废（记录保留），清单状态已按最新凭证复核",
                adService.voidEvidence(id, req));
    }

    // ---------------- 批量重算 ----------------

    @PostMapping("/recalc-jobs")
    public ApiResponse<Views.AdRecalcJobView> createJob(@Valid @RequestBody AdDtos.RecalcJobReq req) {
        return ApiResponse.success("批量重算已开始（后台执行，可继续查询与登记飞行）", adService.createJob(req));
    }

    @GetMapping("/recalc-jobs")
    public ApiResponse<List<Views.AdRecalcJobView>> jobs() {
        return ApiResponse.success(adService.jobs());
    }

    @GetMapping("/recalc-jobs/{id}")
    public ApiResponse<Views.AdRecalcJobView> job(@PathVariable("id") Long id) {
        return ApiResponse.success(adService.job(id));
    }

    @GetMapping("/recalc-jobs/{id}/items")
    public ApiResponse<List<Views.AdRecalcItemView>> jobItems(@PathVariable("id") Long id) {
        return ApiResponse.success(adService.jobItems(id));
    }

    @PostMapping("/recalc-jobs/{id}/resume")
    public ApiResponse<Views.AdRecalcJobView> resume(@PathVariable("id") Long id) {
        return ApiResponse.success("失败项已开始续跑（成功项不重跑，不重复生成结果）", adService.resumeJob(id));
    }

    private String sanitize(String name) {
        return name.replaceAll("[\\\\/]", "_");
    }
}
