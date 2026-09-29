package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.ReleaseDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.ReleaseService;

import jakarta.validation.Valid;

/**
 * 模块六：出厂放行审签。
 *
 * <p>所有门禁、三人互斥、快照不可变、失效与并发判定都在服务端；前端只是把这些事实摆出来。
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseController {

    private final ReleaseService releaseService;

    public ReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    /** 顶部总览：待审 / 已失效 / 已放行数量（带当前机号的进行中审签与门禁提示） */
    @GetMapping("/overview")
    public ApiResponse<Views.ReleaseOverview> overview(@RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(releaseService.overview(aircraftId));
    }

    /** 发起前门禁检查（阻断条件与出位阻断同一口径） */
    @GetMapping("/gate")
    public ApiResponse<Views.ReleaseGateView> gate(@RequestParam("aircraftId") Long aircraftId) {
        return ApiResponse.success(releaseService.gate(aircraftId));
    }

    /** 审签列表，可按飞机过滤 */
    @GetMapping
    public ApiResponse<List<Views.ReleaseListItem>> list(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(releaseService.list(aircraftId));
    }

    /** 审签详情：当前事实门禁 + 全部快照版本（含差异）+ 完整审计时间线 */
    @GetMapping("/{id}")
    public ApiResponse<Views.ReleaseDetail> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(releaseService.detail(id));
    }

    /** 发起出厂放行（草稿 + v1 证据快照） */
    @PostMapping
    public ApiResponse<Views.ReleaseDetail> create(@Valid @RequestBody ReleaseDtos.CreateReq req) {
        return ApiResponse.success("已发起出厂放行审签，证据快照已生成", releaseService.create(req));
    }

    /** 修订开放故障的保留依据（草稿/已驳回） */
    @PostMapping("/{id}/retain-basis")
    public ApiResponse<Views.ReleaseDetail> retainBasis(@PathVariable("id") Long id,
                                                        @Valid @RequestBody ReleaseDtos.RetainBasisReq req) {
        return ApiResponse.success("保留依据已保存", releaseService.saveRetainBasis(id, req));
    }

    /** 提交进入待机械放行（再次提交生成新版本快照） */
    @PostMapping("/{id}/submit")
    public ApiResponse<Views.ReleaseActionResult> submit(@PathVariable("id") Long id,
                                                         @Valid @RequestBody ReleaseDtos.SubmitReq req) {
        return ApiResponse.success("已提交", releaseService.submit(id, req));
    }

    /** 机械放行 */
    @PostMapping("/{id}/mech-sign")
    public ApiResponse<Views.ReleaseActionResult> mechSign(@PathVariable("id") Long id,
                                                           @Valid @RequestBody ReleaseDtos.SignReq req) {
        return ApiResponse.success("机械放行已签字", releaseService.mechSign(id, req));
    }

    /** 质量终审逐条确认开放故障保留依据 */
    @PostMapping("/{id}/confirm-defect")
    public ApiResponse<Views.ReleaseDetail> confirmDefect(@PathVariable("id") Long id,
                                                          @Valid @RequestBody ReleaseDtos.ConfirmDefectReq req) {
        return ApiResponse.success("已记录终审确认", releaseService.confirmRetainedDefect(id, req));
    }

    /** 质量终审通过 → 已放行 */
    @PostMapping("/{id}/qa-approve")
    public ApiResponse<Views.ReleaseActionResult> qaApprove(@PathVariable("id") Long id,
                                                            @Valid @RequestBody ReleaseDtos.SignReq req) {
        return ApiResponse.success("质量终审通过，飞机已放行", releaseService.qaApprove(id, req));
    }

    /** 驳回（必须留原因，回到可修订状态） */
    @PostMapping("/{id}/reject")
    public ApiResponse<Views.ReleaseActionResult> reject(@PathVariable("id") Long id,
                                                         @Valid @RequestBody ReleaseDtos.RejectReq req) {
        return ApiResponse.success("已驳回", releaseService.reject(id, req));
    }
}
