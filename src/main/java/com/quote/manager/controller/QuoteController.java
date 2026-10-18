package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.QuoteSaveRequest;
import com.quote.manager.entity.Quote;
import com.quote.manager.service.QuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 报价记录接口。
 * 前端的「历史报价」弹窗和「录入新报价」都走这里。
 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    /**
     * 报价分页查询，按报价时间倒序。
     * GET /api/quotes?pageNo=1&pageSize=100&productId=1
     * productId 可选；传了则只看某个产品的历史。
     */
    @GetMapping
    public ApiResponse<Page<Quote>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(quoteService.page(pageNo, pageSize, productId));
    }

    /** 按 id 查单条报价 */
    @GetMapping("/{id}")
    public ApiResponse<Quote> get(@PathVariable Long id) {
        return ApiResponse.ok(quoteService.getById(id));
    }

    /**
     * 录入报价。
     * 业务规则：采购价 ≤ 报价，价格均不能为负。
     * quoteTime 不传默认当前时间。
     */
    @PostMapping
    public ApiResponse<Quote> create(@Valid @RequestBody QuoteSaveRequest req) {
        return ApiResponse.ok(quoteService.create(req));
    }

    /** 修改某条报价（含纠偏报价时间） */
    @PutMapping("/{id}")
    public ApiResponse<Quote> update(@PathVariable Long id, @Valid @RequestBody QuoteSaveRequest req) {
        return ApiResponse.ok(quoteService.update(id, req));
    }

    /** 删除单条报价记录 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        quoteService.delete(id);
        return ApiResponse.ok(null);
    }
}
