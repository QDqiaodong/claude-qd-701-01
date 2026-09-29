package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.AircraftDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.AircraftService;

import jakarta.validation.Valid;

/** 模块二：飞机台账（列表的工卡汇总列来自 @Subselect 只读视图实体）。 */
@RestController
@RequestMapping("/api/aircraft")
public class AircraftController {

    private final AircraftService aircraftService;

    public AircraftController(AircraftService aircraftService) {
        this.aircraftService = aircraftService;
    }

    @GetMapping
    public ApiResponse<List<Views.AircraftView>> list() {
        return ApiResponse.success(aircraftService.list());
    }

    /**
     * 【@Subselect 汇总接口】按飞机聚合工卡数与累计工时，按累计工时倒序。
     * 数据来自 {@code WorkCardSummary}（{@code @Subselect} + {@code @Immutable} 只读视图实体）。
     */
    @GetMapping("/card-summary")
    public ApiResponse<List<Views.CardSummaryView>> cardSummary() {
        return ApiResponse.success(aircraftService.cardSummary());
    }

    @PostMapping
    public ApiResponse<Views.AircraftView> create(@Valid @RequestBody AircraftDtos.AircraftSaveReq req) {
        return ApiResponse.success("飞机已建档", aircraftService.create(req));
    }

    @PostMapping("/{id}/airworthy")
    public ApiResponse<Views.AircraftView> changeAirworthy(@PathVariable("id") Long id,
                                                           @Valid @RequestBody AircraftDtos.AirworthyReq req) {
        return ApiResponse.success("适航状态已更新", aircraftService.changeAirworthy(id, req));
    }

    /** 登记一段飞行，累加总飞行小时与总起落次数 */
    @PostMapping("/{id}/flight-log")
    public ApiResponse<Views.AircraftView> logFlight(@PathVariable("id") Long id,
                                                     @RequestBody AircraftDtos.FlightLogReq req) {
        return ApiResponse.success("飞行数据已登记", aircraftService.logFlight(id, req));
    }
}
