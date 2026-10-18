package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.QuoteSaveRequest;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.QuoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报价记录维护。一条报价挂在某个产品下，同一产品可有多次报价（历史轨迹）。
 */
@Service
@RequiredArgsConstructor
public class QuoteService {

    private final QuoteMapper quoteMapper;
    private final ProductService productService;

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
        validatePrice(req.getPurchasePrice(), req.getQuotePrice());

        Quote quote = new Quote();
        quote.setProductId(req.getProductId());
        quote.setPurchasePrice(req.getPurchasePrice());
        quote.setQuotePrice(req.getQuotePrice());
        quote.setQuoteTime(req.getQuoteTime() != null ? req.getQuoteTime() : LocalDateTime.now());
        quote.setRemark(req.getRemark());
        quoteMapper.insert(quote);
        return quote;
    }

    public Quote update(Long id, QuoteSaveRequest req) {
        Quote existing = getById(id);
        productService.getById(req.getProductId());
        validatePrice(req.getPurchasePrice(), req.getQuotePrice());

        existing.setProductId(req.getProductId());
        existing.setPurchasePrice(req.getPurchasePrice());
        existing.setQuotePrice(req.getQuotePrice());
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

    /**
     * 业务规则：报价不能低于采购价，否则就是亏本报价。
     * 这条规则只有业务方清楚，必须写进代码而不是靠人记。
     */
    private void validatePrice(BigDecimal purchasePrice, BigDecimal quotePrice) {
        if (purchasePrice.compareTo(quotePrice) > 0) {
            throw new BizException("业务规则：采购价不能大于报价（采购价=" + purchasePrice + "，报价=" + quotePrice + "）");
        }
    }
}
