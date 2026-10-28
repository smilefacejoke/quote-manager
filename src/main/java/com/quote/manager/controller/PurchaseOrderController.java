package com.quote.manager.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.quote.manager.common.ApiResponse;
import com.quote.manager.dto.PurchaseOrderDetailVO;
import com.quote.manager.dto.PurchaseOrderListVO;
import com.quote.manager.dto.PurchaseOrderSaveRequest;
import com.quote.manager.entity.PurchaseOrder;
import com.quote.manager.service.InvoiceImportService;
import com.quote.manager.service.TableImportService;
import com.quote.manager.service.PurchaseOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购订单接口。
 * 列表一条 = 一张订单；详情返回该订单的型号明细与计算列。
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class PurchaseOrderController {

    private final PurchaseOrderService purchaseOrderService;
    private final InvoiceImportService invoiceImportService;
    private final TableImportService tableImportService;

    /**
     * 订单分页。
     * GET /api/orders?pageNo=1&pageSize=10&orderNo=&customerName=&month=2026-10
     * month 为 yyyy-MM，按「下订时间」过滤当月订单。
     */
    @GetMapping
    public ApiResponse<Page<PurchaseOrderListVO>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerName,
            @RequestParam(required = false) String month) {
        return ApiResponse.ok(purchaseOrderService.page(pageNo, pageSize, orderNo, customerName, month));
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

    /**
     * 导入采购发票 Excel，生成订单并同步型号报价。
     * POST /api/orders/import-invoice （multipart 字段名 file）
     * To→客户名，Your PO#→订单号，send Date→下订时间(12:00)，
     * Part Number→型号，Price→销售价(USD)，QTY→数量。
     */
    @PostMapping("/import-invoice")
    public ApiResponse<Map<String, Object>> importInvoice(@RequestParam("file") MultipartFile file) {
        InvoiceImportService.InvoiceImportResult r = invoiceImportService.importInvoice(file);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderNo", r.orderNo);
        body.put("customerName", r.customerName);
        body.put("orderTime", r.orderTime);
        body.put("itemsImported", r.itemsImported);
        body.put("productsCreated", r.productsCreated);
        body.put("quotesNeedSupplement", r.quotesNeedSupplement);
        body.put("quotesUpdated", r.quotesUpdated);
        body.put("quotesUnchanged", r.quotesUnchanged);
        body.put("errors", r.errors);
        return ApiResponse.ok(body);
    }

    /**
     * 订单明细表导入 · 预览（不落库）。
     * POST /api/orders/import-table/preview （multipart file）
     * 列：型号/数量/采购额/销售价（美元）等中文表头。
     */
    @PostMapping("/import-table/preview")
    public ApiResponse<Map<String, Object>> previewTable(@RequestParam("file") MultipartFile file) {
        TableImportService.PreviewResult r = tableImportService.preview(file);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("suggestedOrderNo", r.suggestedOrderNo);
        body.put("orderTime", r.orderTime);
        body.put("items", r.items);
        body.put("totalReceived", r.totalReceived);
        body.put("totalPurchase", r.totalPurchase);
        body.put("expectedReceipt", r.expectedReceipt);
        return ApiResponse.ok(body);
    }

    /**
     * 订单明细表导入 · 确认入库（客户名必填，订单号自动）。
     * POST /api/orders/import-table/commit
     * body: { customerName, items: [{model, quantity, purchaseAmount, salesUsd}] }
     */
    @PostMapping("/import-table/commit")
    public ApiResponse<Map<String, Object>> commitTable(@RequestBody Map<String, Object> body) {
        String customerName = body.get("customerName") == null ? null : String.valueOf(body.get("customerName"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
        List<TableImportService.ItemRow> items = new java.util.ArrayList<>();
        if (rawItems != null) {
            for (Map<String, Object> raw : rawItems) {
                TableImportService.ItemRow row = new TableImportService.ItemRow();
                row.model = raw.get("model") == null ? null : String.valueOf(raw.get("model"));
                if (raw.get("quantity") != null) {
                    row.quantity = (int) Double.parseDouble(String.valueOf(raw.get("quantity")));
                }
                if (raw.get("purchaseAmount") != null && String.valueOf(raw.get("purchaseAmount")).length() > 0) {
                    row.purchaseAmount = new java.math.BigDecimal(String.valueOf(raw.get("purchaseAmount")));
                }
                if (raw.get("salesUsd") != null && String.valueOf(raw.get("salesUsd")).length() > 0) {
                    row.salesUsd = new java.math.BigDecimal(String.valueOf(raw.get("salesUsd")));
                }
                items.add(row);
            }
        }
        com.quote.manager.entity.PurchaseOrder order = (com.quote.manager.entity.PurchaseOrder) tableImportService.commit(customerName, items);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", order.getId());
        resp.put("orderNo", order.getOrderNo());
        resp.put("customerName", order.getCustomerName());
        return ApiResponse.ok(resp);
    }
}
