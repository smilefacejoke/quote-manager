package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.dto.ProductWithQuoteVO;
import com.quote.manager.entity.Product;
import com.quote.manager.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 产品档案接口。
 * 列表返回的是「产品 + 最新报价」聚合视图，供列表页直接展示价格。
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

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
     * 导出产品（含最新报价）为 CSV，筛选条件与列表一致。
     * GET /api/products/export?brand=&model=
     * Excel 直接打开：带 UTF-8 BOM，中文不乱码。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String model) {
        // 本地工具类，一次导出全量匹配行；量大再改分页/流式
        List<ProductWithQuoteVO> rows = productService.listWithLatestQuote(brand, model);

        String header = "ID,品牌,型号,备注,采购价,报价,数量,报价时间,建档时间";
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String body = rows.stream()
                .map(p -> String.join(",",
                        String.valueOf(p.getId()),
                        csv(p.getBrand()),
                        csv(p.getModel()),
                        csv(p.getRemark()),
                        p.getPurchasePrice() != null ? p.getPurchasePrice().toPlainString() : "",
                        p.getQuotePrice() != null ? p.getQuotePrice().toPlainString() : "",
                        p.getQuantity() != null ? String.valueOf(p.getQuantity()) : "",
                        p.getQuoteTime() != null ? p.getQuoteTime().format(fmt) : "",
                        p.getCreatedAt() != null ? p.getCreatedAt().format(fmt) : ""))
                .collect(Collectors.joining("\n"));

        // UTF-8 BOM：Excel 打开中文不乱码
        byte[] content = ("\uFEFF" + header + "\n" + body + "\n").getBytes(StandardCharsets.UTF_8);

        String filename = URLEncoder.encode(
                "产品报价_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv",
                StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + filename)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(content.length)
                .body(content);
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

    /** CSV 字段转义：有逗号/引号/换行时加引号包裹 */
    private static String csv(String raw) {
        if (raw == null) {
            return "";
        }
        if (raw.contains(",") || raw.contains("\"") || raw.contains("\n")) {
            return "\"" + raw.replace("\"", "\"\"") + "\"";
        }
        return raw;
    }
}
