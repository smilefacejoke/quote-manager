package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.common.PriceRules;
import com.quote.manager.dto.QuoteSaveRequest;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.QuoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 报价记录维护。一条报价挂在某个产品下，同一产品可有多次报价（历史轨迹）。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteMapper quoteMapper;
    private final ProductService productService;
    private final ExchangeRateService exchangeRateService;

    /** 按产品过滤，报价时间倒序（最新在前） */
    public Page<Quote> page(int pageNo, int pageSize, Long productId) {
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        if (productId != null) {
            qw.eq(Quote::getProductId, productId);
        }
        qw.orderByDesc(Quote::getQuoteTime);
        return quoteMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    public Quote getById(Long id) {
        Quote quote = quoteMapper.selectById(id);
        if (quote == null) {
            throw new BizException("报价记录不存在: " + id);
        }
        return quote;
    }

    /** 录入报价；报价时间不传则默认当前时间 */
    public Quote create(QuoteSaveRequest req) {
        productService.getById(req.getProductId());
        PriceRules.validateNotBelowCost(req.getPurchasePrice(), req.getQuotePrice());

        Quote quote = new Quote();
        quote.setProductId(req.getProductId());
        quote.setPurchasePrice(req.getPurchasePrice());
        quote.setQuotePrice(req.getQuotePrice());
        quote.setQuantity(req.getQuantity());
        applyUsd(quote);
        quote.setQuoteTime(req.getQuoteTime() != null ? req.getQuoteTime() : LocalDateTime.now());
        quote.setRemark(req.getRemark());
        quoteMapper.insert(quote);
        return quote;
    }

    public Quote update(Long id, QuoteSaveRequest req) {
        Quote existing = getById(id);
        productService.getById(req.getProductId());
        PriceRules.validateNotBelowCost(req.getPurchasePrice(), req.getQuotePrice());

        existing.setProductId(req.getProductId());
        existing.setPurchasePrice(req.getPurchasePrice());
        existing.setQuotePrice(req.getQuotePrice());
        existing.setQuantity(req.getQuantity());
        applyUsd(existing);
        if (req.getQuoteTime() != null) {
            existing.setQuoteTime(req.getQuoteTime());
        }
        existing.setRemark(req.getRemark());
        quoteMapper.updateById(existing);
        return existing;
    }

    public void delete(Long id) {
        getById(id);
        quoteMapper.deleteById(id);
    }

    /** 按当日业务汇率写入报价USD与总额USD（总额=USD×数量，数量为空则总额为空） */
    private void applyUsd(Quote quote) {
        BigDecimal rate = exchangeRateService.getTodayUsedRate();
        quote.setQuoteUsd(ExchangeRateService.cnyToUsd(quote.getQuotePrice(), rate));
        if (quote.getQuantity() != null && quote.getQuoteUsd() != null) {
            quote.setQuoteTotalUsd(quote.getQuoteUsd()
                    .multiply(BigDecimal.valueOf(quote.getQuantity()))
                    .setScale(2, RoundingMode.HALF_UP));
        } else {
            quote.setQuoteTotalUsd(null);
        }
    }


    /**
     * 老报价批量回填 USD：只补 quote_usd 为空的记录。
     * 使用「当日业务汇率」（向下取1位），与新报价口径一致。
     * @return 回填条数
     */
    public int backfillUsd() {
        BigDecimal rate = exchangeRateService.getTodayUsedRate();
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.isNull(Quote::getQuoteUsd);
        java.util.List<Quote> pending = quoteMapper.selectList(qw);
        int n = 0;
        for (Quote q : pending) {
            applyUsd(q);
            quoteMapper.updateById(q);
            n++;
        }
        return n;
    }

}
