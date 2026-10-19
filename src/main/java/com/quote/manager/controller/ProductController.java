package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.dto.ProductWithQuoteVO;
import com.quote.manager.entity.Product;
import com.quote.manager.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
}
