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
import com.aircraft.mro.dto.DefectDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.DefectService;

import jakarta.validation.Valid;

/** 模块四：故障保留。 */
@RestController
@RequestMapping("/api/defects")
public class DefectController {

    private final DefectService defectService;

    public DefectController(DefectService defectService) {
        this.defectService = defectService;
    }

    @GetMapping
    public ApiResponse<List<Views.DefectView>> list(
            @RequestParam(value = "aircraftId", required = false) Long aircraftId) {
        return ApiResponse.success(defectService.list(aircraftId));
    }

    @GetMapping("/overdue")
    public ApiResponse<List<Views.DefectView>> overdue() {
        return ApiResponse.success(defectService.overdue());
    }

    @PostMapping
    public ApiResponse<Views.DefectView> create(@Valid @RequestBody DefectDtos.DefectSaveReq req) {
        return ApiResponse.success("缺陷已登记", defectService.create(req));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<Views.DefectView> close(@PathVariable("id") Long id) {
        return ApiResponse.success("缺陷已关闭", defectService.close(id));
    }
}
