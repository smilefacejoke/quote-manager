package com.quote.manager.service;

import com.quote.manager.common.BizException;
import com.quote.manager.common.PriceRules;
import com.quote.manager.entity.Product;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.ProductMapper;
import com.quote.manager.mapper.QuoteMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 批量导入：一行 = 一个型号的一次报价。
 * 列顺序：Brand | Model | Num | Purchase price | Quote price | Price(USD/pcs) | Total | noted
 * USD 列有值则直接采用表格数据（含公式缓存值）；为空才按当日业务汇率换算。
 * 报价总额 = 报价USD × 数量；报价时间取导入时刻。
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final ProductMapper productMapper;
    private final QuoteMapper quoteMapper;
    private final ExchangeRateService exchangeRateService;

    public static class ImportResult {
        public int productsCreated;
        public int productsMatched;
        public int quotesCreated;
        public final List<String> errors = new ArrayList<>();
    }

    @Transactional
    public ImportResult importWorkbook(MultipartFile file) {
        ImportResult result = new ImportResult();
        if (file == null || file.isEmpty()) {
            throw new BizException("请先选择 Excel 文件");
        }
        // 汇率延迟获取：表格 USD 列都有值时无需联网取汇率
        BigDecimal[] rateHolder = new BigDecimal[1];
        LocalDateTime now = LocalDateTime.now();
        // 品牌+型号 → 产品，同一次导入内复用
        Map<String, Product> cache = new HashMap<>();

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String brand = text(cell(row, 0));
                String model = text(cell(row, 1));
                // 跳过表头
                if (r == 0 && isHeader(brand, model)) {
                    continue;
                }
                // 型号必填；品牌可空
                if (model == null || model.isBlank()) {
                    if (brand != null && !brand.isBlank()) {
                        result.errors.add("第 " + (r + 1) + " 行：型号为空，已跳过");
                    }
                    continue;
                }
                brand = (brand == null || brand.isBlank()) ? null : brand.trim();
                model = model.trim();

                try {
                    String noted = text(cell(row, 7));
                    Product product = findOrCreate(brand, model, noted, result, cache);
                    Quote quote = buildQuote(product.getId(), row, rateHolder, now, result, r);
                    if (quote != null) {
                        quoteMapper.insert(quote);
                        result.quotesCreated++;
                    }
                } catch (Exception e) {
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    result.errors.add("第 " + (r + 1) + " 行：" + msg);
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BizException("Excel 解析失败：" + msg);
        }

        if (result.quotesCreated == 0 && result.productsCreated == 0) {
            throw new BizException("没有导入任何数据，请检查表头与内容格式");
        }
        return result;
    }

    /**
     * 按品牌+型号找产品，没有则新建。
     * noted 对应列表「备注」写入产品；已有产品且 noted 非空时更新备注。
     */
    private Product findOrCreate(String brand, String model, String noted,
                                 ImportResult result, Map<String, Product> cache) {
        String key = (brand == null ? "" : brand) + "\u0000" + model;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        LambdaQueryWrapper<Product> qw = new LambdaQueryWrapper<>();
        if (brand == null) {
            qw.and(w -> w.isNull(Product::getBrand).or().eq(Product::getBrand, ""));
        } else {
            qw.eq(Product::getBrand, brand);
        }
        qw.eq(Product::getModel, model);
        Product existing = productMapper.selectOne(qw);
        if (existing != null) {
            if (noted != null && !noted.isBlank()) {
                existing.setRemark(noted.trim());
                productMapper.updateById(existing);
            }
            result.productsMatched++;
            cache.put(key, existing);
            return existing;
        }
        Product product = new Product();
        product.setBrand(brand);
        product.setModel(model);
        product.setRemark(noted != null && !noted.isBlank() ? noted.trim() : null);
        product.setCreatedAt(LocalDateTime.now());
        productMapper.insert(product);
        result.productsCreated++;
        cache.put(key, product);
        return product;
    }

    private Quote buildQuote(Long productId, Row row, BigDecimal[] rateHolder, LocalDateTime now,
                             ImportResult result, int rowIndex) {
        // 列：0 Brand 1 Model 2 Num 3 Purchase 4 Quote 5 USD 6 Total 7 noted
        Integer qty = intVal(cell(row, 2));
        BigDecimal purchase = decimal(cell(row, 3));
        BigDecimal quotePrice = decimal(cell(row, 4));
        BigDecimal sheetUsd = decimal(cell(row, 5));
        String noted = text(cell(row, 7));

        if (purchase == null && quotePrice == null && qty == null && sheetUsd == null) {
            result.errors.add("第 " + (rowIndex + 1) + " 行：数量/价格均为空，已跳过");
            return null;
        }
        if (purchase == null) {
            purchase = BigDecimal.ZERO;
        }
        if (quotePrice == null) {
            quotePrice = purchase;
        }
        if (qty == null) {
            qty = 1;
        }
        PriceRules.validateNotBelowCost(purchase, quotePrice);

        Quote quote = new Quote();
        quote.setProductId(productId);
        quote.setPurchasePrice(purchase);
        quote.setQuotePrice(quotePrice);
        quote.setQuantity(qty);
        quote.setQuoteTime(now);
        quote.setRemark(noted);
        // USD：表格里有值（含公式结果）就直接用，没有才按当日业务汇率换算
        if (sheetUsd != null) {
            quote.setQuoteUsd(sheetUsd);
        } else {
            if (rateHolder[0] == null) {
                rateHolder[0] = exchangeRateService.getTodayUsedRate();
            }
            quote.setQuoteUsd(ExchangeRateService.cnyToUsd(quotePrice, rateHolder[0]));
        }
        if (quote.getQuoteUsd() != null) {
            quote.setQuoteTotalUsd(quote.getQuoteUsd()
                    .multiply(BigDecimal.valueOf(qty))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        return quote;
    }

    private boolean isHeader(String brand, String model) {
        String b = brand == null ? "" : brand.toLowerCase();
        String m = model == null ? "" : model.toLowerCase();
        return b.contains("brand") || b.contains("品牌") || m.contains("model") || m.contains("型号");
    }

    private Cell cell(Row row, int idx) {
        return row.getCell(idx, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
    }

    private String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.STRING) {
            return cell.getStringCellValue();
        }
        if (type == CellType.NUMERIC) {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toString();
            }
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d) && !Double.isInfinite(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        return null;
    }

    private Integer intVal(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.NUMERIC) {
            return (int) Math.round(cell.getNumericCellValue());
        }
        String t = text(cell);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(t.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal decimal(Cell cell) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        if (type == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        String t = text(cell);
        if (t == null || t.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(t.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
