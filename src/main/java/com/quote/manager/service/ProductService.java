package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.ProductSaveRequest;
import com.quote.manager.entity.Product;
import com.quote.manager.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;

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

    public void delete(Long id) {
        getById(id);
        productMapper.deleteById(id);
    }

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
