package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.dto.ProductWithQuoteVO;
import com.quote.manager.entity.Product;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.ProductMapper;
import com.quote.manager.mapper.QuoteMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 产品维护 + 产品视角的报价聚合。
 * 报价的录入/删除见 {@link QuoteService}。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final QuoteMapper quoteMapper;

    /**
     * 产品分页，并附带每条产品「最新一条」报价。
     * 列表页展示采购价/报价用最新值；历史轨迹走报价查询接口。
     */
    public Page<ProductWithQuoteVO> pageWithLatestQuote(int pageNo, int pageSize, String brand, String model) {
        Page<Product> productPage = page(pageNo, pageSize, brand, model);
        List<Product> products = productPage.getRecords();
        if (products.isEmpty()) {
            return toEmptyVoPage(productPage);
        }

        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, Quote> latestByProduct = fetchLatestQuotes(productIds);

        List<ProductWithQuoteVO> voList = products.stream().map(p -> {
            ProductWithQuoteVO vo = new ProductWithQuoteVO();
            vo.setId(p.getId());
            vo.setBrand(p.getBrand());
            vo.setModel(p.getModel());
            vo.setSpec(p.getSpec());
            vo.setCreatedAt(p.getCreatedAt());
            Quote latest = latestByProduct.get(p.getId());
            if (latest != null) {
                vo.setLatestQuoteId(latest.getId());
                vo.setPurchasePrice(latest.getPurchasePrice());
                vo.setQuotePrice(latest.getQuotePrice());
                vo.setQuoteTime(latest.getQuoteTime());
            }
            return vo;
        }).toList();

        Page<ProductWithQuoteVO> result = new Page<>(productPage.getCurrent(), productPage.getSize(), productPage.getTotal());
        result.setRecords(voList);
        return result;
    }

    /**
     * 批量取出各产品的最新报价。
     * 本地小数据量下「查全量再归并」比写关联子查询更直观；数据量上来再换 SQL。
     */
    private Map<Long, Quote> fetchLatestQuotes(List<Long> productIds) {
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.in(Quote::getProductId, productIds);
        List<Quote> quotes = quoteMapper.selectList(qw);
        return quotes.stream().collect(Collectors.toMap(
                Quote::getProductId,
                Function.identity(),
                // 同一产品的多条报价：时间更晚者更新；时间相同则 id 更大者视为更新
                (a, b) -> {
                    if (a.getQuoteTime() == null && b.getQuoteTime() == null) {
                        return a.getId() > b.getId() ? a : b;
                    }
                    if (a.getQuoteTime() == null) return b;
                    if (b.getQuoteTime() == null) return a;
                    int cmp = a.getQuoteTime().compareTo(b.getQuoteTime());
                    if (cmp != 0) return cmp > 0 ? a : b;
                    return a.getId() > b.getId() ? a : b;
                }
        ));
    }

    private Page<ProductWithQuoteVO> toEmptyVoPage(Page<Product> productPage) {
        Page<ProductWithQuoteVO> result = new Page<>(productPage.getCurrent(), productPage.getSize(), productPage.getTotal());
        result.setRecords(Collections.emptyList());
        return result;
    }

    /** 品牌/型号模糊过滤，按 id 倒序 */
    public Page<Product> page(int pageNo, int pageSize, String brand, String model) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(brand)) {
            qw.like(Product::getBrand, brand);
        }
        if (StringUtils.hasText(model)) {
            qw.like(Product::getModel, model);
        }
        qw.orderByDesc(Product::getId);
        return productMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    public Product getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BizException("产品不存在: " + id);
        }
        return product;
    }

    public Product create(ProductSaveRequest req) {
        checkDuplicate(req.getBrand(), req.getModel(), null);
        Product product = new Product();
        product.setBrand(req.getBrand().trim());
        product.setModel(req.getModel().trim());
        product.setSpec(req.getSpec());
        productMapper.insert(product);
        return product;
    }

    public Product update(Long id, ProductSaveRequest req) {
        Product existing = getById(id);
        checkDuplicate(req.getBrand(), req.getModel(), id);
        existing.setBrand(req.getBrand().trim());
        existing.setModel(req.getModel().trim());
        existing.setSpec(req.getSpec());
        productMapper.updateById(existing);
        return existing;
    }

    /**
     * 删除产品时必须一并清掉它的报价历史。
     * 否则 quote 表留下孤儿记录，后续按产品统计会对不上账。
     * 整个删除放在同一事务里：报价删失败则产品也不删。
     */
    @Transactional
    public void delete(Long id) {
        Product product = getById(id);
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.eq(Quote::getProductId, product.getId());
        quoteMapper.delete(qw);
        productMapper.deleteById(product.getId());
    }

    /** 同品牌 + 同型号视为同一物料，不允许重复建档 */
    private void checkDuplicate(String brand, String model, Long excludeId) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        qw.eq(Product::getBrand, brand.trim())
          .eq(Product::getModel, model.trim());
        if (excludeId != null) {
            qw.ne(Product::getId, excludeId);
        }
        if (productMapper.selectCount(qw) > 0) {
            throw new BizException("同品牌同型号已存在: " + brand + " / " + model);
        }
    }
}
