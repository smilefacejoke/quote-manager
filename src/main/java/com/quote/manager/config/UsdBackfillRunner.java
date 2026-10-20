package com.quote.manager.config;

import com.quote.manager.service.QuoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把历史报价缺失的 USD 字段按当日业务汇率补齐。
 * 只处理 quote_usd 为空的行，幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsdBackfillRunner implements CommandLineRunner {

    private final QuoteService quoteService;

    @Override
    public void run(String... args) {
        try {
            int n = quoteService.backfillUsd();
            if (n > 0) {
                log.info("历史报价 USD 回填完成，共 {} 条", n);
            }
        } catch (Exception e) {
            log.warn("启动时 USD 回填跳过: {}", e.getMessage());
        }
    }
}
