package com.quote.manager.service;

import com.quote.manager.common.BizException;
import com.quote.manager.dto.PurchaseOrderSaveRequest;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 订单明细表导入（中文表头）。
 * <p>
 * 列对应：型号 | 数量 | 实际收款 | 采购额 | 销售额 | 利润 | 实际总利润 |
 * 销售价（美元）| 汇率 | 销售价（人民币）
 * <p>
 * 流程两步：preview 只解析不落库 → commit 校验客户名后生成订单（订单号自动）。
 * 采购额若填成负数，按成本取绝对值，保证利润 = 销售额 − 采购额。
 */
@Service
@RequiredArgsConstructor
public class TableImportService {

    private static final int MONEY_SCALE = 2;

    private final PurchaseOrderService purchaseOrderService;
    private final ExchangeRateService exchangeRateService;

    /** 预览结果：不落库 */
    public static class PreviewResult {
        public String suggestedOrderNo;
        public LocalDateTime orderTime;
        public List<ItemRow> items = new ArrayList<>();
        /** 实际收款合计（表内各行之和，可空） */
        public BigDecimal totalReceived;
        public BigDecimal totalPurchase;
        public BigDecimal expectedReceipt;
    }

    public static class ItemRow {
        public String model;
        public Integer quantity;
        public BigDecimal purchaseAmount;
        public BigDecimal salesUsd;
    }

    /** 解析表格，仅预览 */
    public PreviewResult preview(MultipartFile file) {
        List<ItemRow> items = parseItems(file);
        PreviewResult r = new PreviewResult();
        r.suggestedOrderNo = purchaseOrderService.generateOrderNo();
        r.orderTime = LocalDateTime.now();
        r.items = items;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal purchase = BigDecimal.ZERO;
        BigDecimal expected = BigDecimal.ZERO;
        for (ItemRow it : items) {
            // 表内可能没写实际收款；写了则合计到订单级
            purchase = purchase.add(it.purchaseAmount == null ? BigDecimal.ZERO : it.purchaseAmount);
            if (it.salesUsd != null && it.quantity != null) {
                BigDecimal rate = exchangeRateService.getTodayUsedRate();
                expected = expected.add(it.salesUsd
                        .multiply(rate)
                        .multiply(BigDecimal.valueOf(it.quantity))
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            }
        }
        r.totalPurchase = purchase.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        r.expectedReceipt = expected.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        r.totalReceived = received.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return r;
    }

    /**
     * 确认入库：客户名必填，订单号自动。
     * items 由预览行 + 用户确认后提交。
     */
    @org.springframework.transaction.annotation.Transactional
    public Object commit(String customerName, List<ItemRow> items) {
        if (!StringUtils.hasText(customerName)) {
            throw new BizException("请填写客户名称后再导入");
        }
        if (items == null || items.isEmpty()) {
            throw new BizException("没有可导入的明细行");
        }
        PurchaseOrderSaveRequest req = new PurchaseOrderSaveRequest();
        req.setOrderNo(purchaseOrderService.generateOrderNo());
        req.setCustomerName(customerName.trim());
        req.setOrderTime(LocalDateTime.now());
        List<PurchaseOrderSaveRequest.Item> list = new ArrayList<>();
        for (ItemRow row : items) {
            if (!StringUtils.hasText(row.model)) {
                throw new BizException("型号不能为空");
            }
            PurchaseOrderSaveRequest.Item it = new PurchaseOrderSaveRequest.Item();
            it.setModel(row.model.trim());
            it.setQuantity(row.quantity != null ? row.quantity : 1);
            it.setPurchaseAmount(row.purchaseAmount);
            it.setSalesUsd(row.salesUsd);
            list.add(it);
        }
        req.setItems(list);
        return purchaseOrderService.create(req);
    }

    private List<ItemRow> parseItems(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请先选择表格文件");
        }
        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Map<String, Integer> cols = detectColumns(sheet);
            if (!cols.containsKey("model")) {
                throw new BizException("未找到「型号」列，请检查表头");
            }
            List<ItemRow> items = new ArrayList<>();
            int headerRow = cols.get("__headerRow");
            for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String model = text(cell(row, cols.get("model")));
                if (model == null || model.isBlank()) continue;
                String m = model.trim();
                if (m.contains("合计") || m.equalsIgnoreCase("total")) continue;

                ItemRow item = new ItemRow();
                item.model = m;
                item.quantity = intVal(cell(row, cols.getOrDefault("quantity", -1)));
                item.purchaseAmount = absIfNegative(decimal(cell(row, cols.getOrDefault("purchase", -1))));
                item.salesUsd = decimal(cell(row, cols.getOrDefault("salesUsd", -1)));
                if (item.salesUsd == null) {
                    // 回退：用销售价（人民币）/ 汇率
                    BigDecimal cny = decimal(cell(row, cols.getOrDefault("salesCny", -1)));
                    BigDecimal rate = decimal(cell(row, cols.getOrDefault("rate", -1)));
                    if (cny != null && rate != null && rate.compareTo(BigDecimal.ZERO) > 0) {
                        item.salesUsd = cny.divide(rate, 2, RoundingMode.HALF_UP);
                    }
                }
                items.add(item);
            }
            if (items.isEmpty()) {
                throw new BizException("表格里没有明细行");
            }
            return items;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BizException("表格解析失败：" + msg);
        }
    }

    /** 采购额负数取绝对值（表内用减号表示成本） */
    private BigDecimal absIfNegative(BigDecimal v) {
        if (v == null) return null;
        return v.signum() < 0 ? v.abs() : v;
    }

    private Map<String, Integer> detectColumns(Sheet sheet) {
        Map<String, Integer> cols = new HashMap<>();
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 30); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < 15; c++) {
                String v = text(cell(row, c));
                if (v == null) continue;
                String k = v.trim().toLowerCase(Locale.ROOT).replace(" ", "")
                        .replace("（", "(").replace("）", ")");
                if (k.contains("型号") || k.equals("model") || k.contains("part")) {
                    cols.putIfAbsent("model", c);
                } else if (k.contains("数量") || k.equals("qty") || k.equals("quantity")) {
                    cols.putIfAbsent("quantity", c);
                } else if (k.contains("实际收款") || k.contains("实际收")) {
                    cols.putIfAbsent("received", c);
                } else if (k.contains("采购")) {
                    cols.putIfAbsent("purchase", c);
                } else if (k.contains("销售额") || k.equals("amount")) {
                    cols.putIfAbsent("salesAmount", c);
                } else if (k.contains("利润") && k.contains("实际")) {
                    cols.putIfAbsent("actualProfit", c);
                } else if (k.equals("利润") || k.equals("profit")) {
                    cols.putIfAbsent("profit", c);
                } else if (k.contains("销售价") && (k.contains("美元") || k.contains("usd") || k.contains("$"))) {
                    cols.putIfAbsent("salesUsd", c);
                } else if (k.contains("销售价") && (k.contains("人民币") || k.contains("cny") || k.contains("元"))) {
                    cols.putIfAbsent("salesCny", c);
                } else if (k.contains("汇率") || k.equals("rate")) {
                    cols.putIfAbsent("rate", c);
                }
            }
            if (cols.containsKey("model")) {
                cols.put("__headerRow", r);
                break;
            }
        }
        return cols;
    }

    private Cell cell(Row row, int idx) {
        return idx < 0 || row == null ? null : row.getCell(idx);
    }

    private String text(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    return stripDotZero(cell.getNumericCellValue());
                }
                return cell.getStringCellValue();
            } catch (Exception e) {
                return null;
            }
        }
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                return stripDotZero(cell.getNumericCellValue());
            default:
                return null;
        }
    }

    private String stripDotZero(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private Integer intVal(Cell cell) {
        String t = text(cell);
        if (t == null || t.isBlank()) return null;
        try {
            return (int) Math.round(Double.parseDouble(t.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal decimal(Cell cell) {
        String t = text(cell);
        if (t == null || t.isBlank()) return null;
        try {
            return new BigDecimal(t.trim().replace(",", "").replace("−", "-"));
        } catch (Exception e) {
            return null;
        }
    }
}
