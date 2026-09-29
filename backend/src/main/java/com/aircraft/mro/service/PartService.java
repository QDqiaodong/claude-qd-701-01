package com.aircraft.mro.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aircraft.mro.common.BizException;
import com.aircraft.mro.domain.Part;
import com.aircraft.mro.domain.ToolLoan;
import com.aircraft.mro.dto.PartDtos;
import com.aircraft.mro.dto.Views;
import com.aircraft.mro.repository.PartRepository;
import com.aircraft.mro.repository.ToolLoanRepository;

/**
 * 模块五：航材与借还。
 *
 * <p>规则面：
 * <ol>
 *   <li>件号唯一；库存 / 最低库存 / 单价不能为负</li>
 *   <li>库存低于最低库存即预警（前端标红），库存为 0 时禁止借出</li>
 *   <li>借出数量不得超过现有库存，借出即扣减库存</li>
 *   <li>已归还的单据不能重复归还；归还数量不得大于借出数量，允许分次归还</li>
 * </ol>
 */
@Service
public class PartService {

    private final PartRepository partRepository;
    private final ToolLoanRepository loanRepository;

    public PartService(PartRepository partRepository, ToolLoanRepository loanRepository) {
        this.partRepository = partRepository;
        this.loanRepository = loanRepository;
    }

    @Transactional(readOnly = true)
    public List<Views.PartView> listParts() {
        List<Views.PartView> list = new ArrayList<>();
        for (Part part : partRepository.findAllByOrderByPartNoAsc()) {
            list.add(toPartView(part));
        }
        return list;
    }

    @Transactional
    public Views.PartView createPart(PartDtos.PartSaveReq req) {
        String partNo = trim(req.getPartNo());
        if (partRepository.existsByPartNo(partNo)) {
            throw new BizException("件号 " + partNo + " 已存在，不能重复建档");
        }
        Integer stockQty = req.getStockQty() == null ? 0 : req.getStockQty();
        Integer minQty = req.getMinQty() == null ? 0 : req.getMinQty();
        BigDecimal unitPrice = req.getUnitPrice() == null ? BigDecimal.ZERO : req.getUnitPrice();
        if (stockQty < 0 || minQty < 0) {
            throw new BizException("库存数量与最低库存不能为负数");
        }
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException("单价不能为负数");
        }
        Part part = new Part();
        part.setPartNo(partNo);
        part.setName(trim(req.getName()));
        part.setSpec(trim(req.getSpec()));
        part.setUnit(req.getUnit() == null || req.getUnit().isBlank() ? "件" : req.getUnit().trim());
        part.setStockQty(stockQty);
        part.setMinQty(minQty);
        part.setUnitPrice(unitPrice);
        part.setLocation(req.getLocation());
        part.setUpdatedAt(LocalDateTime.now());
        return toPartView(partRepository.save(part));
    }

    @Transactional(readOnly = true)
    public List<Views.LoanView> listLoans(Long partId) {
        List<ToolLoan> loans = partId == null
                ? loanRepository.findAllByOrderByLoanTimeDesc()
                : loanRepository.findAllByPartIdOrderByLoanTimeDesc(partId);
        Map<Long, Part> partMap = partMap();
        List<Views.LoanView> list = new ArrayList<>();
        for (ToolLoan loan : loans) {
            list.add(toLoanView(loan, partMap));
        }
        return list;
    }

    /** 借出：扣库存并生成借出单 */
    @Transactional
    public Views.LoanView loanOut(PartDtos.LoanReq req) {
        Long partId = req.getPartId();
        if (partId == null) {
            throw new BizException("请先选择要借出的航材件");
        }
        Part part = partRepository.findById(partId).orElseThrow(() -> new BizException("航材件不存在：id=" + partId));
        Integer quantity = req.getQuantity() == null || req.getQuantity() <= 0 ? 1 : req.getQuantity();
        if (part.getStockQty() <= 0) {
            throw new BizException("件号 " + part.getPartNo() + " 库存为 0，无法借出");
        }
        if (quantity > part.getStockQty()) {
            throw new BizException("件号 " + part.getPartNo() + " 现有库存 " + part.getStockQty()
                    + " " + part.getUnit() + "，不足借出 " + quantity);
        }
        part.setStockQty(part.getStockQty() - quantity);
        part.setUpdatedAt(LocalDateTime.now());
        partRepository.save(part);

        ToolLoan loan = new ToolLoan();
        loan.setLoanNo(nextLoanNo());
        loan.setPartId(part.getId());
        loan.setBorrower(trim(req.getBorrower()));
        loan.setPurpose(req.getPurpose());
        loan.setQuantity(quantity);
        loan.setLoanTime(LocalDateTime.now());
        loan.setReturnTime(null);
        loan.setHandler(req.getHandler() == null || req.getHandler().isBlank() ? "库管吴敏" : req.getHandler().trim());
        ToolLoan saved = loanRepository.save(loan);
        Map<Long, Part> partMap = new HashMap<>();
        partMap.put(part.getId(), part);
        return toLoanView(saved, partMap);
    }

    /** 归还：回冲库存；可分次归还，还完才置归还时间 */
    @Transactional
    public Views.LoanView returnLoan(Long id, PartDtos.ReturnReq req) {
        ToolLoan loan = loanRepository.findById(id).orElseThrow(() -> new BizException("借还单不存在：id=" + id));
        if (loan.getReturnTime() != null) {
            throw new BizException("借还单 " + loan.getLoanNo() + " 已归还，不能重复归还");
        }
        Part part = partRepository.findById(loan.getPartId())
                .orElseThrow(() -> new BizException("航材件不存在：id=" + loan.getPartId()));
        Integer quantity = req == null || req.getQuantity() == null || req.getQuantity() <= 0
                ? loan.getQuantity()
                : req.getQuantity();
        if (quantity > loan.getQuantity()) {
            throw new BizException("归还数量 " + quantity + " 大于借出数量 " + loan.getQuantity() + "，不合规则");
        }
        part.setStockQty(part.getStockQty() + quantity);
        part.setUpdatedAt(LocalDateTime.now());
        partRepository.save(part);

        if (quantity.equals(loan.getQuantity())) {
            loan.setReturnTime(LocalDateTime.now());
        } else {
            loan.setQuantity(loan.getQuantity() - quantity);
        }
        ToolLoan saved = loanRepository.save(loan);
        Map<Long, Part> partMap = new HashMap<>();
        partMap.put(part.getId(), part);
        return toLoanView(saved, partMap);
    }

    private String nextLoanNo() {
        long count = loanRepository.count();
        return String.format("TL-%d-%04d", LocalDateTime.now().getYear(), count + 1);
    }

    private Map<Long, Part> partMap() {
        Map<Long, Part> map = new HashMap<>();
        for (Part part : partRepository.findAllByOrderByPartNoAsc()) {
            map.put(part.getId(), part);
        }
        return map;
    }

    private Views.PartView toPartView(Part part) {
        BigDecimal value = part.getUnitPrice().multiply(BigDecimal.valueOf(part.getStockQty()));
        return new Views.PartView(part.getId(), part.getPartNo(), part.getName(), part.getSpec(), part.getUnit(),
                part.getStockQty(), part.getMinQty(), part.getUnitPrice(), part.getLocation(),
                part.getStockQty() < part.getMinQty(), value.setScale(2, RoundingMode.HALF_UP),
                part.getUpdatedAt());
    }

    private Views.LoanView toLoanView(ToolLoan loan, Map<Long, Part> partMap) {
        Part part = partMap.get(loan.getPartId());
        return new Views.LoanView(loan.getId(), loan.getLoanNo(), loan.getPartId(),
                part == null ? null : part.getPartNo(), part == null ? null : part.getName(),
                part == null ? null : part.getUnit(), loan.getBorrower(), loan.getPurpose(), loan.getQuantity(),
                loan.getLoanTime(), loan.getReturnTime(), loan.getHandler(), loan.getReturnTime() != null);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
