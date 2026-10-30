package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.common.PriceRules;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final ExchangeRateService exchangeRateService;

    /**
     * 产品分页，并附带每条产品「最新一条」报价。
     * 列表页展示采购价/报价用最新值；历史轨迹走报价查询接口。
     */
    public Page<ProductWithQuoteVO> pageWithLatestQuote(int pageNo, int pageSize, String brand, String model,
                                                        String month) {
        Page<Product> productPage = page(pageNo, pageSize, brand, model, month);
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
            vo.setRemark(p.getRemark());
            vo.setCreatedAt(p.getCreatedAt());
            Quote latest = latestByProduct.get(p.getId());
            if (latest != null) {
                vo.setLatestQuoteId(latest.getId());
                vo.setPurchasePrice(latest.getPurchasePrice());
                vo.setQuotePrice(latest.getQuotePrice());
                vo.setQuantity(latest.getQuantity());
                vo.setQuoteUsd(latest.getQuoteUsd());
                vo.setQuoteTotalUsd(latest.getQuoteTotalUsd());
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

    /** 全量导出用：按筛选条件取全部产品 + 最新报价，不分页 */
    public List<ProductWithQuoteVO> listWithLatestQuote(String brand, String model) {
        Page<Product> productPage = page(1, Integer.MAX_VALUE, brand, model, null);
        return attachLatestQuotes(productPage.getRecords());
    }

    /** 批量导出用：按 id 取产品 + 最新报价；顺序与列表一致（id 倒序） */
    public List<ProductWithQuoteVO> listByIdsWithLatestQuote(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Product> products = productMapper.selectBatchIds(distinctIds);
        products.sort(Comparator.comparing(Product::getId).reversed());
        return attachLatestQuotes(products);
    }

    private List<ProductWithQuoteVO> attachLatestQuotes(List<Product> products) {
        if (products.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> productIds = products.stream().map(Product::getId).toList();
        Map<Long, Quote> latestByProduct = fetchLatestQuotes(productIds);

        return products.stream().map(p -> {
            ProductWithQuoteVO vo = new ProductWithQuoteVO();
            vo.setId(p.getId());
            vo.setBrand(p.getBrand());
            vo.setModel(p.getModel());
            vo.setRemark(p.getRemark());
            vo.setCreatedAt(p.getCreatedAt());
            Quote latest = latestByProduct.get(p.getId());
            if (latest != null) {
                vo.setLatestQuoteId(latest.getId());
                vo.setPurchasePrice(latest.getPurchasePrice());
                vo.setQuotePrice(latest.getQuotePrice());
                vo.setQuantity(latest.getQuantity());
                vo.setQuoteUsd(latest.getQuoteUsd());
                vo.setQuoteTotalUsd(latest.getQuoteTotalUsd());
                vo.setQuoteTime(latest.getQuoteTime());
            }
            return vo;
        }).toList();
    }

    /** 品牌/型号模糊过滤，按 id 倒序；month=yyyy-MM 时只要「该月有报价」的产品 */
    public Page<Product> page(int pageNo, int pageSize, String brand, String model, String month) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(brand)) {
            qw.like(Product::getBrand, brand);
        }
        if (StringUtils.hasText(model)) {
            qw.like(Product::getModel, model);
        }
        if (StringUtils.hasText(month)) {
            String m = month.trim();
            if (!m.matches("\\d{4}-\\d{2}")) {
                throw new BizException("月份格式应为 yyyy-MM");
            }
            java.time.YearMonth ym = java.time.YearMonth.parse(m);
            List<Long> idsInMonth = quoteMapper.selectList(new LambdaQueryWrapper<Quote>()
                            .select(Quote::getProductId)
                            .ge(Quote::getQuoteTime, ym.atDay(1).atStartOfDay())
                            .lt(Quote::getQuoteTime, ym.plusMonths(1).atDay(1).atStartOfDay()))
                    .stream()
                    .map(Quote::getProductId)
                    .distinct()
                    .toList();
            if (idsInMonth.isEmpty()) {
                return new Page<>(pageNo, pageSize, 0);
            }
            qw.in(Product::getId, idsInMonth);
        }
        qw.orderByDesc(Product::getId);
        return productMapper.selectPage(new Page<>(pageNo, pageSize), qw);
    }

    /** 品牌下拉用：去重后的全部品牌，按字典序 */
    /**
     * 品牌下拉选项。品牌允许为空，这里跳过空品牌。
     * 显式判空，避免 stream 方法引用对 null 品牌抛 NPE。
     */
    public List<String> listBrands() {
        List<Product> products = productMapper.selectList(new LambdaQueryWrapper<Product>()
                .select(Product::getBrand));
        List<String> brands = new java.util.ArrayList<>();
        for (Product p : products) {
            String b = (p == null) ? null : p.getBrand();
            if (b != null) {
                String t = b.trim();
                if (!t.isEmpty()) {
                    brands.add(t);
                }
            }
        }
        return brands.stream().distinct().sorted().toList();
    }

    public Product getById(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            throw new BizException("产品不存在: " + id);
        }
        return product;
    }

    /**
     * 新增产品；若请求里带了价格，同事务顺带写入第一条报价。
     * 价格字段可选，但必须成对填写且采购价 ≤ 报价。
     */
    @Transactional
    public Product create(ProductSaveRequest req) {
        checkDuplicate(req.getBrand(), req.getModel(), null);
        Product product = new Product();
        product.setBrand(trimToNull(req.getBrand()));
        product.setModel(req.getModel().trim());
        product.setRemark(req.getRemark());
        // DB 默认值不会回填到实体，这里主动赋值，保证创建响应里能看到建档时间
        product.setCreatedAt(LocalDateTime.now());
        productMapper.insert(product);

        createInitialQuoteIfNeeded(product.getId(), req);
        return product;
    }

    /** 价格都留空则只建档不报价；填了才校验并落一条历史 */
    private void createInitialQuoteIfNeeded(Long productId, ProductSaveRequest req) {
        if (req.getPurchasePrice() == null && req.getQuotePrice() == null) {
            return;
        }
        PriceRules.validatePair(req.getPurchasePrice(), req.getQuotePrice());
        PriceRules.validateNotBelowCost(req.getPurchasePrice(), req.getQuotePrice());

        Quote quote = new Quote();
        quote.setProductId(productId);
        quote.setPurchasePrice(req.getPurchasePrice());
        quote.setQuotePrice(req.getQuotePrice());
        quote.setQuantity(req.getQuantity());
        applyUsd(quote);
        quote.setQuoteTime(req.getQuoteTime() != null ? req.getQuoteTime() : LocalDateTime.now());
        quote.setRemark("建档报价");
        quoteMapper.insert(quote);
    }

    /**
     * 更新产品基础信息；若请求带了价格/数量，就地修改「最新一条」报价（不新增历史）。
     * 编辑场景的报价时间取当前时间。
     */
    @Transactional
    public Product update(Long id, ProductSaveRequest req) {
        Product existing = getById(id);
        checkDuplicate(req.getBrand(), req.getModel(), id);
        existing.setBrand(trimToNull(req.getBrand()));
        existing.setModel(req.getModel().trim());
        existing.setRemark(req.getRemark());
        productMapper.updateById(existing);

        updateLatestQuoteIfNeeded(existing.getId(), req);
        return existing;
    }

    /** 编辑保存：有价格或数量就改最新那条报价；产品还没有报价且填了价格则补建一条 */
    private void updateLatestQuoteIfNeeded(Long productId, ProductSaveRequest req) {
        boolean hasPrice = req.getPurchasePrice() != null || req.getQuotePrice() != null;
        boolean hasQty = req.getQuantity() != null;
        if (!hasPrice && !hasQty) {
            return;
        }
        if (hasPrice) {
            PriceRules.validatePair(req.getPurchasePrice(), req.getQuotePrice());
            PriceRules.validateNotBelowCost(req.getPurchasePrice(), req.getQuotePrice());
        }

        Quote latest = findLatestQuote(productId);
        if (latest == null) {
            if (!hasPrice) {
                // 只填数量、没有历史报价时无法挂在报价上，忽略数量
                return;
            }
            Quote quote = new Quote();
            quote.setProductId(productId);
            quote.setPurchasePrice(req.getPurchasePrice());
            quote.setQuotePrice(req.getQuotePrice());
            quote.setQuantity(req.getQuantity());
            applyUsd(quote);
            quote.setQuoteTime(req.getQuoteTime() != null ? req.getQuoteTime() : LocalDateTime.now());
            quote.setRemark("编辑补录");
            quoteMapper.insert(quote);
            return;
        }

        if (hasPrice) {
            latest.setPurchasePrice(req.getPurchasePrice());
            latest.setQuotePrice(req.getQuotePrice());
        }
        if (hasQty) {
            latest.setQuantity(req.getQuantity());
        }
        applyUsd(latest);
        // 编辑动作时间即为本次报价时间
        latest.setQuoteTime(req.getQuoteTime() != null ? req.getQuoteTime() : LocalDateTime.now());
        quoteMapper.updateById(latest);
    }

    /** 某产品报价时间最新的一条（时间相同取 id 更大） */
    private Quote findLatestQuote(Long productId) {
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.eq(Quote::getProductId, productId);
        qw.orderByDesc(Quote::getQuoteTime).orderByDesc(Quote::getId);
        List<Quote> quotes = quoteMapper.selectList(qw);
        return quotes.isEmpty() ? null : quotes.get(0);
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

    /**
     * 批量删除产品：每个产品连同其全部历史报价一并删除。
     * 整批放在同一事务里：任一失败则整批不生效，避免删一半。
     *
     * @return 实际删除的产品数（忽略不存在的 id）
     */
    /**
     * 批量编辑：只改传入字段。
     * brand 传了就改（空串 = 清空品牌）；quantity 传了就改各产品「最新报价」的数量。
     * 两者都未传则不改任何东西。
     */
    @Transactional
    public int updateBatch(List<Long> ids, String brand, Integer quantity, boolean hasBrand, boolean hasQuantity) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException("请先选择要编辑的产品");
        }
        if (!hasBrand && !hasQuantity) {
            throw new BizException("请至少填写一项要修改的内容");
        }
        List<Long> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<Product> products = productMapper.selectBatchIds(distinctIds);
        if (products.isEmpty()) {
            throw new BizException("所选产品不存在");
        }
        for (Product product : products) {
            if (hasBrand) {
                String b = brand == null ? null : brand.trim();
                product.setBrand(b == null || b.isEmpty() ? null : b);
                productMapper.updateById(product);
            }
            if (hasQuantity) {
                Quote latest = findLatestQuote(product.getId());
                if (latest != null) {
                    latest.setQuantity(quantity);
                    quoteMapper.updateById(latest);
                }
            }
        }
        return products.size();
    }

    @Transactional
    public int deleteBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException("请先选择要删除的产品");
        }
        List<Long> distinctIds = ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (distinctIds.isEmpty()) {
            throw new BizException("请先选择要删除的产品");
        }
        List<Product> products = productMapper.selectBatchIds(distinctIds);
        if (products.isEmpty()) {
            throw new BizException("所选产品不存在");
        }
        List<Long> existingIds = products.stream().map(Product::getId).toList();
        LambdaQueryWrapper<Quote> qw = new LambdaQueryWrapper<>();
        qw.in(Quote::getProductId, existingIds);
        quoteMapper.delete(qw);
        productMapper.deleteBatchIds(existingIds);
        return existingIds.size();
    }

    /** 同品牌 + 同型号视为同一物料；品牌可空，空品牌按「无品牌」匹配 */
    private void checkDuplicate(String brand, String model, Long excludeId) {
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        String b = trimToNull(brand);
        if (b == null) {
            qw.and(w -> w.isNull(Product::getBrand).or().eq(Product::getBrand, ""));
        } else {
            qw.eq(Product::getBrand, b);
        }
        qw.eq(Product::getModel, model.trim());
        if (excludeId != null) {
            qw.ne(Product::getId, excludeId);
        }
        if (productMapper.selectCount(qw) > 0) {
            throw new BizException("同品牌同型号已存在: " + (b == null ? "(无品牌)" : b) + " / " + model);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 按当日业务汇率写入报价USD与总额USD */
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

}
