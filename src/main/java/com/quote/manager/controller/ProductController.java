package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.entity.Product;
import com.quote.manager.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public ApiResponse<Page<Product>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String model) {
        return ApiResponse.ok(productService.page(pageNo, pageSize, brand, model));
    }

    @GetMapping("/{id}")
    public ApiResponse<Product> get(@PathVariable Long id) {
        return ApiResponse.ok(productService.getById(id));
    }

    @PostMapping
    public ApiResponse<Product> create(@Valid @RequestBody ProductSaveRequest req) {
        return ApiResponse.ok(productService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Product> update(@PathVariable Long id, @Valid @RequestBody ProductSaveRequest req) {
        return ApiResponse.ok(productService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ApiResponse.ok(null);
    }
}
