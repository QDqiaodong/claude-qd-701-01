package com.aircraft.mro.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.HangarService;

/** 机库总览：顶栏计数器。 */
@RestController
@RequestMapping("/api/hangar")
public class HangarController {

    private final HangarService hangarService;

    public HangarController(HangarService hangarService) {
        this.hangarService = hangarService;
    }

    @GetMapping("/overview")
    public ApiResponse<Views.Overview> overview() {
        return ApiResponse.success(hangarService.overview());
    }
}
