package com.quote.manager.controller;

import com.quote.manager.common.ApiResponse;
import com.quote.manager.entity.ExchangeRate;
import com.quote.manager.service.ExchangeRateService;
import com.quote.manager.service.QuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 当日美元汇率查询与老报价 USD 回填 */
@RestController
@RequestMapping("/api/rates")
@RequiredArgsConstructor
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;
    private final QuoteService quoteService;

    /**
     * 今日美元汇率。
     * rateDisplay：界面灰色小字，精确值 3 位小数四舍五入；
     * rateUsed：报价USD计算用，向下取 1 位小数。
     * GET /api/rates/today
     */
    @GetMapping("/today")
    public ApiResponse<Map<String, Object>> today() {
        ExchangeRate rate = exchangeRateService.getToday();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rateDate", rate.getRateDate());
        body.put("usdCnyRaw", rate.getUsdCnyRaw());
        body.put("rateDisplay", ExchangeRateService.displayRate(rate.getUsdCnyRaw()));
        body.put("rateUsed", rate.getUsdCnyUsed());
        return ApiResponse.ok(body);
    }

    /**
     * 老报价批量回填 USD（只补空的）。
     * POST /api/rates/backfill-usd
     */
    @PostMapping("/backfill-usd")
    public ApiResponse<Map<String, Object>> backfill() {
        int n = quoteService.backfillUsd();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("backfilled", n);
        body.put("rateUsed", exchangeRateService.getTodayUsedRate());
        return ApiResponse.ok(body);
    }
}
