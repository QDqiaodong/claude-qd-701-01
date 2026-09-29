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
import com.aircraft.mro.dto.PartDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.service.PartService;

import jakarta.validation.Valid;

/** 模块五：航材与借还。 */
@RestController
@RequestMapping("/api/parts")
public class PartController {

    private final PartService partService;

    public PartController(PartService partService) {
        this.partService = partService;
    }

    @GetMapping
    public ApiResponse<List<Views.PartView>> listParts() {
        return ApiResponse.success(partService.listParts());
    }

    @PostMapping
    public ApiResponse<Views.PartView> createPart(@Valid @RequestBody PartDtos.PartSaveReq req) {
        return ApiResponse.success("航材件已建档", partService.createPart(req));
    }

    @GetMapping("/loans")
    public ApiResponse<List<Views.LoanView>> listLoans(
            @RequestParam(value = "partId", required = false) Long partId) {
        return ApiResponse.success(partService.listLoans(partId));
    }

    @PostMapping("/loans")
    public ApiResponse<Views.LoanView> loanOut(@Valid @RequestBody PartDtos.LoanReq req) {
        return ApiResponse.success("航材已借出", partService.loanOut(req));
    }

    @PostMapping("/loans/{id}/return")
    public ApiResponse<Views.LoanView> returnLoan(@PathVariable("id") Long id,
                                                  @RequestBody(required = false) PartDtos.ReturnReq req) {
        return ApiResponse.success("航材已归还", partService.returnLoan(id, req));
    }
}
