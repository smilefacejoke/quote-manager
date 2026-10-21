package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.dto.ProductWithQuoteVO;
import com.quote.manager.entity.Product;
import com.quote.manager.service.ExcelExportService;
import com.quote.manager.service.ExcelImportService;
import com.quote.manager.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品档案接口。
 * 列表返回的是「产品 + 最新报价」聚合视图，供列表页直接展示价格。
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ExcelImportService excelImportService;
    private final ExcelExportService excelExportService;

    /**
     * 产品分页查询。
     * GET /api/products?pageNo=1&pageSize=10&brand=华为&model=Mate
     * brand/model 为模糊匹配，可只传其中一个。
     */
    @GetMapping
    public ApiResponse<Page<ProductWithQuoteVO>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String model) {
        return ApiResponse.ok(productService.pageWithLatestQuote(pageNo, pageSize, brand, model));
    }

    /**
     * 品牌下拉选项：库内去重品牌（字典序）。
     * GET /api/products/brands
     */
    @GetMapping("/brands")
    public ApiResponse<List<String>> brands() {
        return ApiResponse.ok(productService.listBrands());
    }

    /**
     * 批量导出选中产品（含最新报价）为 Excel。
     * POST /api/products/export-excel ，body 为 id 数组，如 [1,2,3]
     * 列名英文：Brand | Model | Purchase Price | Quote Price | Quote USD | Total USD
     * 最后一行为 Total USD 合计。
     */
    @PostMapping("/export-excel")
    public ResponseEntity<byte[]> exportExcel(@RequestBody List<Long> ids) {
        List<ProductWithQuoteVO> rows = productService.listByIdsWithLatestQuote(ids);
        if (rows.isEmpty()) {
            throw new BizException("请先选择要导出的产品");
        }
        byte[] content = excelExportService.export(rows);
        String filename = URLEncoder.encode(
                "产品报价_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".xlsx",
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(new MediaType("application", "vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(content.length)
                .body(content);
    }

    /**
     * 批量导入 Excel：一行一个型号的一次报价。
     * 列序：Brand | Model | Num | Purchase price | Quote price | Price(USD/pcs) | Total | noted
     * USD 列有值则直接采用；为空才按当日业务汇率换算。报价总额 = USD × 数量。
     * 报价时间取导入时刻。
     * POST /api/products/import （multipart 字段名 file）
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importExcel(@RequestParam("file") MultipartFile file) {
        ExcelImportService.ImportResult r = excelImportService.importWorkbook(file);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("productsCreated", r.productsCreated);
        body.put("productsMatched", r.productsMatched);
        body.put("quotesCreated", r.quotesCreated);
        body.put("errors", r.errors);
        return ApiResponse.ok(body);
    }

    /** 按 id 查单个产品（不含报价信息） */
    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.getById(id));
    }

    /**
     * 新增产品；品牌+型号不能重复。
     * purchasePrice / quotePrice / quoteTime 均可选——填了价格则同事务写入第一条报价
     * （价格需成对填写，且采购价 ≤ 报价）。
     */
    @PostMapping
    public ApiResponse<Product> create(@Valid @RequestBody ProductSaveRequest req) {
        return ApiResponse.ok(productService.create(req));
    }

    /** 更新产品基础信息；不影响历史报价 */
    @PutMapping("/{id}")
    public ApiResponse<Product> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest req) {
        return ApiResponse.ok(productService.update(id, req));
    }

    /**
     * 删除产品，会级联删除该产品的全部历史报价。
     * 前端务必先弹窗二次确认。
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.ok(null);
    }

    /**
     * 批量删除产品，会级联删除各自的全部历史报价。
     * POST /api/products/batch-delete ，body 为 id 数组，如 [1,2,3]
     * 前端务必先弹窗二次确认。
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Map<String, Object>> deleteBatch(@RequestBody List<Long> ids) {
        int deleted = productService.deleteBatch(ids);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", deleted);
        return ApiResponse.ok(body);
    }
}
