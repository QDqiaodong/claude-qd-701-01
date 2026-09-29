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
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.dto.WorkCardDtos;
import com.aircraft.mro.service.WorkCardService;

import jakarta.validation.Valid;

/** 模块三：定检工卡与执行流水。 */
@RestController
@RequestMapping("/api/work-cards")
public class WorkCardController {

    private final WorkCardService workCardService;

    public WorkCardController(WorkCardService workCardService) {
        this.workCardService = workCardService;
    }

    @GetMapping
    public ApiResponse<List<Views.WorkCardView>> list(@RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(workCardService.list(aircraftId));
    }

    /** 竖向时间轴：派工 → 开工 → 完工 → 检验 → 关闭 */
    @GetMapping("/timeline")
    public ApiResponse<List<Views.TimelineNode>> timeline(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(workCardService.timeline(aircraftId));
    }

    @PostMapping
    public ApiResponse<Views.WorkCardView> create(@Valid @RequestBody WorkCardDtos.CardSaveReq req) {
        return ApiResponse.success("工卡已开具", workCardService.create(req));
    }

    /** 待派工 → 进行中 */
    @PostMapping("/{id}/start")
    public ApiResponse<Views.WorkCardView> start(@PathVariable("id") Long id,
                                                 @Valid @RequestBody WorkCardDtos.StartReq req) {
        return ApiResponse.success("工卡已开工", workCardService.start(id, req));
    }

    /** 进行中 → 待检验 */
    @PostMapping("/{id}/finish")
    public ApiResponse<Views.WorkCardView> finish(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) WorkCardDtos.FinishReq req) {
        return ApiResponse.success("工卡已报完工，等待检验", workCardService.finish(id, req));
    }

    /** 待检验 → 已关闭 */
    @PostMapping("/{id}/inspect")
    public ApiResponse<Views.WorkCardView> inspect(@PathVariable("id") Long id,
                                                   @Valid @RequestBody WorkCardDtos.InspectReq req) {
        return ApiResponse.success("工卡检验通过并关闭", workCardService.inspect(id, req));
    }

    /** 取消工卡 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<Views.WorkCardView> cancel(@PathVariable("id") Long id) {
        return ApiResponse.success("工卡已取消", workCardService.cancel(id));
    }

    /** 模块七：已关闭工卡重新打开（退回待检验），相关适航指令落实回待核实 */
    @PostMapping("/{id}/reopen")
    public ApiResponse<Views.WorkCardView> reopen(@PathVariable("id") Long id,
                                                  @Valid @RequestBody WorkCardDtos.ReopenReq req) {
        return ApiResponse.success("工卡已重新打开，相关适航指令落实结果转为待核实",
                workCardService.reopen(id, req.getOperator()));
    }

    /** 模块七：工卡改挂到另一架飞机，原机适航指令落实回待核实（关联历史保留） */
    @PostMapping("/{id}/reassign")
    public ApiResponse<Views.WorkCardView> reassign(@PathVariable("id") Long id,
                                                    @Valid @RequestBody WorkCardDtos.ReassignReq req) {
        return ApiResponse.success("工卡已改挂，原飞机的适航指令落实结果转为待核实",
                workCardService.reassign(id, req.getTargetAircraftId(), req.getOperator()));
    }
}
