package com.quote.manager.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.quote.manager.common.BizException;
import com.quote.manager.dto.PurchaseOrderSaveRequest;
import com.quote.manager.entity.Product;
import com.quote.manager.entity.PurchaseOrder;
import com.quote.manager.entity.Quote;
import com.quote.manager.mapper.ProductMapper;
import com.quote.manager.mapper.PurchaseOrderMapper;
import com.quote.manager.mapper.QuoteMapper;
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
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 采购发票（Excel）导入。
 * <p>
 * 表头对应：
 * To → 客户名称；Your PO# → 订单号；send Date → 下订时间（日期 + 当日 12:00）；
 * 明细：Part Number → 型号，Price → 销售价(USD)，QTY → 数量。
 * <p>
 * 型号两步同步：
 * <ol>
 *   <li>按型号查库</li>
 *   <li>已有且最新报价 USD 不同 → 更新报价（采购价保留上次）；相同 → 不动；
 *       没有该型号 → 新建产品，并计入「待补充报价」提示</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class InvoiceImportService {

    private static final int MONEY_SCALE = 2;

    private final ProductMapper productMapper;
    private final QuoteMapper quoteMapper;
    private final PurchaseOrderMapper orderMapper;
    private final PurchaseOrderService purchaseOrderService;
    private final ExchangeRateService exchangeRateService;

    public static class InvoiceImportResult {
        public String orderNo;
        public String customerName;
        public LocalDateTime orderTime;
        public int itemsImported;
        public int productsCreated;
        public int quotesNeedSupplement;
        public int quotesUpdated;
        public int quotesUnchanged;
        public final List<String> errors = new ArrayList<>();
    }

    @Transactional
    public InvoiceImportResult importInvoice(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请先选择发票 Excel 文件");
        }
        InvoiceImportResult result = new InvoiceImportResult();
        String customerName;
        String orderNo;
        LocalDateTime orderTime;
        List<ItemLine> items;

        try (Workbook wb = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            String[] header = scanHeader(sheet);
            customerName = header[0];
            orderNo = header[1];
            orderTime = header[2] == null ? LocalDateTime.now().with(LocalTime.NOON) : LocalDateTime.of(LocalDate.parse(header[2]), LocalTime.NOON);
            items = scanItems(sheet);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new BizException("发票解析失败：" + msg);
        }

        if (!StringUtils.hasText(customerName)) {
            throw new BizException("发票里未找到 To（客户名称）");
        }
        // 无 Your PO# 时按「当日日期 + 当日第几单」自动生成，如 20261023001
        if (!StringUtils.hasText(orderNo)) {
            orderNo = purchaseOrderService.generateOrderNo();
        }
        if (items.isEmpty()) {
            throw new BizException("发票里没有明细行（Part Number / Price / Qty）");
        }
        orderNo = orderNo.trim();
        customerName = customerName.trim();
        if (orderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getOrderNo, orderNo)) > 0) {
            throw new BizException("订单号已存在: " + orderNo);
        }

        // 1) 同步型号/报价；2) 再落订单（销售价用发票 USD）
        BigDecimal rate = exchangeRateService.getTodayUsedRate();
        List<PurchaseOrderSaveRequest.Item> orderItems = new ArrayList<>();
        for (ItemLine line : items) {
            // 运费（Shipping cost 等）只进订单，不写产品/报价库
            if (!PurchaseOrderService.isFreightLine(line.model)) {
                syncProductAndQuote(line, rate, result);
            }
            PurchaseOrderSaveRequest.Item it = new PurchaseOrderSaveRequest.Item();
            it.setModel(line.model);
            it.setQuantity(line.qty);
            it.setSalesUsd(line.priceUsd);
            orderItems.add(it);
        }

        PurchaseOrderSaveRequest req = new PurchaseOrderSaveRequest();
        req.setOrderNo(orderNo);
        req.setCustomerName(customerName);
        req.setOrderTime(orderTime);
        req.setItems(orderItems);
        PurchaseOrder order = purchaseOrderService.create(req);

        result.orderNo = orderNo;
        result.customerName = customerName;
        result.orderTime = orderTime;
        result.itemsImported = items.size();
        return result;
    }

    /**
     * 型号两步处理：
     * 查库 → 有则比对最新报价 USD（不同则更新、采购价保留）；无则建档并计入待补充。
     */
    private void syncProductAndQuote(ItemLine line, BigDecimal rate, InvoiceImportResult result) {
        Product product = findProductByModel(line.model);
        if (product == null) {
            Product created = new Product();
            created.setBrand(null);
            created.setModel(line.model);
            created.setCreatedAt(LocalDateTime.now());
            productMapper.insert(created);
            product = created;
            result.productsCreated++;
            // 新型号：发票 Price 必须落到报价表的报价(USD)，采购价待用户补充
            insertQuoteFromInvoice(product.getId(), line, rate);
            result.quotesNeedSupplement++;
            return;
        }
        Quote latest = findLatestQuote(product.getId());
        if (latest == null) {
            insertQuoteFromInvoice(product.getId(), line, rate);
            result.quotesNeedSupplement++;
            return;
        }
        BigDecimal currentUsd = latest.getQuoteUsd();
        if (currentUsd != null && currentUsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                .compareTo(line.priceUsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0) {
            result.quotesUnchanged++;
            return;
        }
        // 不相同 → 更新最新报价：报价USD=发票价，报价CNY=USD×汇率，采购价保留上次
        latest.setQuoteUsd(line.priceUsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        latest.setQuotePrice(line.priceUsd.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        latest.setQuantity(line.qty);
        latest.setQuoteTotalUsd(latest.getQuoteUsd()
                .multiply(BigDecimal.valueOf(line.qty))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        latest.setQuoteTime(LocalDateTime.now());
        quoteMapper.updateById(latest);
        result.quotesUpdated++;
    }

    /**
     * 按发票 Price 写入报价：报价USD=Price，报价CNY=USD×汇率，数量=发票Qty。
     * 采购价库内约束非空，先落 0 占位，提示用户补充真实采购价。
     */
    private void insertQuoteFromInvoice(Long productId, ItemLine line, BigDecimal rate) {
        BigDecimal usd = line.priceUsd.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Quote q = new Quote();
        q.setProductId(productId);
        q.setPurchasePrice(BigDecimal.ZERO);
        q.setQuotePrice(line.priceUsd.multiply(rate).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        q.setQuantity(line.qty);
        q.setQuoteUsd(usd);
        q.setQuoteTotalUsd(usd.multiply(BigDecimal.valueOf(line.qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        q.setQuoteTime(LocalDateTime.now());
        q.setRemark("发票导入，采购价待补充");
        quoteMapper.insert(q);
    }

    private Product findProductByModel(String model) {
        return productMapper.selectList(new LambdaQueryWrapper<Product>()
                .eq(Product::getModel, model)).stream().findFirst().orElse(null);
    }

    private Quote findLatestQuote(Long productId) {
        List<Quote> quotes = quoteMapper.selectList(new LambdaQueryWrapper<Quote>()
                .eq(Quote::getProductId, productId)
                .orderByDesc(Quote::getQuoteTime)
                .orderByDesc(Quote::getId));
        return quotes.isEmpty() ? null : quotes.get(0);
    }

    // ===== 解析 =====

    private static class ItemLine {
        String model;
        Integer qty;
        BigDecimal priceUsd;
    }

    /** [customer, orderNo, orderDate ISO] */
    private String[] scanHeader(Sheet sheet) {
        String customer = null;
        String orderNo = null;
        String orderDate = null;
        for (int r = 0; r <= Math.min(sheet.getLastRowNum(), 40); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < 12; c++) {
                String label = text(cell(row, c));
                if (label == null) continue;
                String key = label.replace(":", "").replace("：", "").trim().toLowerCase(Locale.ROOT);
                String value = nextText(row, c);
                if (value == null) continue;
                if (customer == null && (key.equals("to") || key.equals("客户") || key.startsWith("to "))) {
                    customer = value;
                } else if (orderNo == null && (key.contains("your po") || key.contains("po#") || key.contains("po №")
                        || key.equals("po") || key.contains("订单号"))) {
                    orderNo = value;
                } else if (orderDate == null && (key.contains("send date") || key.contains("date")
                        || key.contains("下订") || key.contains("日期"))) {
                    // 避免误抓 Company date 之类：要求值像日期
                    String d = normalizeDate(value);
                    if (d != null) orderDate = d;
                }
            }
            if (customer != null && orderNo != null && orderDate != null) break;
        }
        return new String[]{customer, orderNo, orderDate};
    }

    private List<ItemLine> scanItems(Sheet sheet) {
        List<ItemLine> items = new ArrayList<>();
        int headerRow = -1;
        int colModel = -1, colQty = -1, colPrice = -1;
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < 15; c++) {
                String v = text(cell(row, c));
                if (v == null) continue;
                String k = v.trim().toLowerCase(Locale.ROOT).replace(" ", "");
                if (colModel < 0 && (k.contains("partnumber") || k.equals("part") || k.contains("型号"))) {
                    colModel = c;
                } else if (colQty < 0 && (k.equals("qty") || k.equals("quantity") || k.contains("数量"))) {
                    colQty = c;
                } else if (colPrice < 0 && (k.equals("price") || k.equals("price(usd") || k.contains("单价"))) {
                    colPrice = c;
                }
            }
            if (colModel >= 0 && colPrice >= 0) {
                headerRow = r;
                break;
            }
        }
        if (headerRow < 0 || colModel < 0 || colPrice < 0) {
            return items;
        }
        if (colQty < 0) colQty = colPrice - 1; // 常见排布：Qty 在 Price 左侧

        for (int r = headerRow + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            String model = text(cell(row, colModel));
            if (model == null || model.isBlank()) {
                // 空型号：可能是 Total 行或预留空行
                continue;
            }
            String m = model.trim();
            if (m.equalsIgnoreCase("total") || m.equalsIgnoreCase("part number") || m.contains("合计")) {
                continue;
            }
            Integer qty = intVal(cell(row, colQty));
            BigDecimal price = decimal(cell(row, colPrice));
            if (price == null) {
                continue;
            }
            ItemLine line = new ItemLine();
            line.model = m;
            line.qty = qty != null && qty > 0 ? qty : 1;
            line.priceUsd = price;
            items.add(line);
        }
        return items;
    }

    /** 同行右侧第一个非空文本 */
    private String nextText(Row row, int fromCol) {
        for (int c = fromCol + 1; c < fromCol + 6 && c < 20; c++) {
            String v = text(cell(row, c));
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    /** 支持 Excel 日期单元格与常见字符串 */
    private String normalizeDate(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // Excel 数字日期在 text() 里已转成本地日期串
        String[] patterns = {
                "yyyy/MM/dd", "yyyy-MM-dd", "yyyy/M/d", "yyyy.MM.dd",
                "MM/dd/yyyy", "dd/MM/yyyy", "yyyyMMdd"
        };
        for (String p : patterns) {
            try {
                return LocalDate.parse(s, DateTimeFormatter.ofPattern(p)).toString();
            } catch (Exception ignore) {
                // try next
            }
        }
        // 截取前 10 位再试
        if (s.length() >= 10) {
            String head = s.substring(0, 10).replace('.', '/').replace('-', '/');
            try {
                String[] parts = head.split("/");
                if (parts.length == 3) {
                    int y = Integer.parseInt(parts[0]);
                    if (parts[0].length() == 4) {
                        return LocalDate.of(y, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])).toString();
                    }
                }
            } catch (Exception ignore) {
                // fallthrough
            }
        }
        return null;
    }

    private Cell cell(Row row, int idx) {
        return row == null ? null : row.getCell(idx);
    }

    private String text(Cell cell) {
        if (cell == null) return null;
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                if (cell.getCachedFormulaResultType() == CellType.NUMERIC) {
                    if (DateUtil.isCellDateFormatted(cell)) {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    }
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
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
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
            return new BigDecimal(t.trim().replace(",", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
