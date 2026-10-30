package com.quote.manager.service;

import com.quote.manager.common.BizException;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.dto.QuoteSaveRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务规则回归：报价不能亏本。
 * 以后改报价逻辑前先跑这个，规则别被悄悄改坏。
 */
@SpringBootTest
class QuoteServiceRuleTest {

    @Autowired
    private QuoteService quoteService;

    @Autowired
    private ProductService productService;

    private Long anyProductId() {
        var page = productService.page(1, 1, null, null, null);
        if (!page.getRecords().isEmpty()) {
            return page.getRecords().get(0).getId();
        }
        ProductSaveRequest req = new ProductSaveRequest();
        req.setBrand("测试品牌");
        req.setModel("测试型号-" + System.nanoTime());
        return productService.create(req).getId();
    }

    @Test
    @DisplayName("采购价大于报价应被拒绝")
    void rejectWhenPurchasePriceGreaterThanQuotePrice() {
        QuoteSaveRequest req = new QuoteSaveRequest();
        req.setProductId(anyProductId());
        req.setPurchasePrice(new BigDecimal("100"));
        req.setQuotePrice(new BigDecimal("90"));

        BizException ex = assertThrows(BizException.class, () -> quoteService.create(req));
        assertTrue(ex.getMessage().contains("采购价不能大于报价"));
    }

    @Test
    @DisplayName("采购价等于报价允许通过")
    void allowWhenPurchasePriceEqualsQuotePrice() {
        QuoteSaveRequest req = new QuoteSaveRequest();
        req.setProductId(anyProductId());
        req.setPurchasePrice(new BigDecimal("100.00"));
        req.setQuotePrice(new BigDecimal("100.00"));

        assertDoesNotThrow(() -> quoteService.create(req));
    }
}
