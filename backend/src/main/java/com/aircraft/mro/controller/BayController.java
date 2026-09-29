package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.BayDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.BayService;

import jakarta.validation.Valid;

/** 模块一：机库机位。 */
@RestController
@RequestMapping("/api/bays")
public class BayController {

    private final BayService bayService;

    public BayController(BayService bayService) {
        this.bayService = bayService;
    }

    @GetMapping
    public ApiResponse<List<Views.BayView>> list() {
        return ApiResponse.success(bayService.list());
    }

    @PostMapping
    public ApiResponse<Views.BayView> create(@Valid @RequestBody BayDtos.BaySaveReq req) {
        return ApiResponse.success("机位已创建", bayService.create(req));
    }

    /** 安排飞机入位 */
    @PostMapping("/{id}/occupy")
    public ApiResponse<Views.BayView> occupy(@PathVariable("id") Long id,
                                             @RequestBody BayDtos.OccupyReq req) {
        return ApiResponse.success("飞机已入位", bayService.occupy(id, req));
    }

    /** 飞机出位 */
    @PostMapping("/{id}/release")
    public ApiResponse<Views.BayView> release(@PathVariable("id") Long id) {
        return ApiResponse.success("飞机已出位", bayService.release(id));
    }

    /** 机位停用 / 重新启用 */
    @PostMapping("/{id}/status")
    public ApiResponse<Views.BayView> changeStatus(@PathVariable("id") Long id,
                                                   @Valid @RequestBody BayDtos.BayStatusReq req) {
        return ApiResponse.success("机位状态已更新", bayService.changeStatus(id, req));
    }
}
