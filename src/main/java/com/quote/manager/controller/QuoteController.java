package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.QuoteSaveRequest;
import com.quote.manager.entity.Quote;
import com.quote.manager.service.QuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class QuoteController {

    private final QuoteService quoteService;

    @GetMapping
    public ApiResponse<Page<Quote>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long productId) {
        return ApiResponse.ok(quoteService.page(pageNo, pageSize, productId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Quote> get(@PathVariable Long id) {
        return ApiResponse.ok(quoteService.getById(id));
    }

    @PostMapping
    public ApiResponse<Quote> create(@Valid @RequestBody QuoteSaveRequest req) {
        return ApiResponse.ok(quoteService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Quote> update(@PathVariable Long id, @Valid @RequestBody QuoteSaveRequest req) {
        return ApiResponse.ok(quoteService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        quoteService.delete(id);
        return ApiResponse.ok(null);
    }
}
