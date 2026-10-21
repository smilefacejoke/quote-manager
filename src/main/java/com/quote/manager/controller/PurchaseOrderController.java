package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.PurchaseOrderDetailVO;
import com.quote.manager.dto.PurchaseOrderListVO;
import com.quote.manager.dto.PurchaseOrderSaveRequest;
import com.quote.manager.entity.PurchaseOrder;
import com.quote.manager.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 采购订单接口。
 * 列表一条 = 一张订单；详情返回该订单的型号明细与计算列。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;

    /**
     * 订单分页。
     * GET /api/orders?pageNo=1&pageSize=10&orderNo=&customerName=
     */
    @GetMapping
    public ApiResponse<Page<PurchaseOrderListVO>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerName) {
        return ApiResponse.ok(purchaseOrderService.page(pageNo, pageSize, orderNo, customerName));
    }

    /** 订单详情（含明细计算列） */
    @GetMapping("/{id}")
    public ApiResponse<PurchaseOrderDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(purchaseOrderService.detail(id));
    }

    /** 新建订单 + 明细；价格由服务端按最新报价/汇率计算 */
    @PostMapping
    public ApiResponse<PurchaseOrder> create(@Valid @RequestBody PurchaseOrderSaveRequest req) {
        return ApiResponse.ok(purchaseOrderService.create(req));
    }

    /** 更新订单（整单覆盖明细，价格重算） */
    @PutMapping("/{id}")
    public ApiResponse<PurchaseOrder> update(@PathVariable Long id, @Valid @RequestBody PurchaseOrderSaveRequest req) {
        return ApiResponse.ok(purchaseOrderService.update(id, req));
    }

    /** 删除订单及其全部明细 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        purchaseOrderService.delete(id);
        return ApiResponse.ok(null);
    }
}
