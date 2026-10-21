package com.quote.manager.service;

import com.quote.manager.dto.ProductWithQuoteVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Excel 批量导出：一行一个产品，取最新报价。
 * 列顺序：Brand | Model | Purchase Price | Quote Price | Quote USD | Total USD
 * 最后一行 Total 为各行 Total USD 合计。列名固定英文，便于与导入模板对齐。
 */
@Service
public class ExcelExportService {

    /** 与业务约定一致：金额保留 2 位 */
    private static final int MONEY_SCALE = 2;

    private static final String[] HEADERS = {
            "Brand", "Model", "Purchase Price", "Quote Price", "Quote USD", "Total USD"
    };

    /**
     * 生成 xlsx 字节流。rows 顺序即导出顺序。
     * 无报价的行价格列留空，不计入合计。
     */
    public byte[] export(List<ProductWithQuoteVO> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Products");

            CellStyle headerStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }

            BigDecimal grandTotal = BigDecimal.ZERO;
            int rowIndex = 1;
            for (ProductWithQuoteVO p : rows) {
                Row row = sheet.createRow(rowIndex++);
                text(row, 0, p.getBrand());
                text(row, 1, p.getModel());
                money(row, 2, p.getPurchasePrice());
                money(row, 3, p.getQuotePrice());
                money(row, 4, p.getQuoteUsd());
                money(row, 5, p.getQuoteTotalUsd());
                if (p.getQuoteTotalUsd() != null) {
                    grandTotal = grandTotal.add(p.getQuoteTotalUsd());
                }
            }

            Row totalRow = sheet.createRow(rowIndex);
            Cell label = totalRow.createCell(0);
            label.setCellValue("Total");
            label.setCellStyle(headerStyle);
            Cell totalCell = totalRow.createCell(5);
            totalCell.setCellValue(grandTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP).doubleValue());
            totalCell.setCellStyle(headerStyle);

            for (int i = 0; i < HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Excel 生成失败：" + e.getMessage(), e);
        }
    }

    private void text(Row row, int col, String value) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value);
        }
    }

    private void money(Row row, int col, BigDecimal value) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.setScale(MONEY_SCALE, RoundingMode.HALF_UP).doubleValue());
        }
    }
}
