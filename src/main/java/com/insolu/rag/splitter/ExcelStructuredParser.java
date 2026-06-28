package com.insolu.rag.splitter;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Excel (.xlsx) 结构化解析器。
 * <p>
 * 使用 Apache POI 逐 Sheet 遍历，将表格数据转换为格式良好的 Markdown 表格，
 * 解决 Tika 扁平化提取导致的表头与数据脱离问题。
 * <p>
 * 核心策略：
 * <ul>
 *   <li>前 {@code HEADER_ROWS} 行（默认 1 行）视为表头</li>
 *   <li>当数据行数达到 {@code CHUNK_THRESHOLD}（默认 200 行）时分块，
 *       每个 Chunk 的 Markdown 表格重复包含表头行</li>
 *   <li>跳过完全为空的行</li>
 *   <li>输出格式：{@code # Sheet: [名称]} + Markdown 表格</li>
 * </ul>
 */
public class ExcelStructuredParser {

    private static final Logger log = LoggerFactory.getLogger(ExcelStructuredParser.class);

    /** 表头行数（可配置） */
    private static final int HEADER_ROWS = 1;
    /** 数据行分块阈值（每个 Chunk 最多包含的数据行数，不含表头） */
    private static final int CHUNK_THRESHOLD = 200;

    /**
     * 解析 Excel 文件（流式），返回 Markdown 文本列表。
     */
    public List<String> parse(InputStream inputStream, String fileName) throws IOException {
        return parseInternal(inputStream, fileName, -1);
    }

    /**
     * 解析 Excel 文件（字节数组），返回 Markdown 文本列表。
     */
    public List<String> parse(byte[] fileBytes, String fileName) throws IOException {
        return parseInternal(new ByteArrayInputStream(fileBytes), fileName, fileBytes.length);
    }

    private List<String> parseInternal(InputStream inputStream, String fileName, long fileSize) throws IOException {
        List<String> chunks = new ArrayList<>();

        try (XSSFWorkbook workbook = new XSSFWorkbook(inputStream)) {
            int sheetCount = workbook.getNumberOfSheets();

            for (int s = 0; s < sheetCount; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                // 收集所有行数据
                List<List<String>> allRows = extractRows(sheet);
                if (allRows.isEmpty()) {
                    log.debug("Sheet '{}' 无数据，跳过", sheetName);
                    continue;
                }

                // 分离表头和数据行
                int headerCount = Math.min(HEADER_ROWS, allRows.size());
                List<List<String>> headerRows = allRows.subList(0, headerCount);
                List<List<String>> dataRows = allRows.subList(headerCount, allRows.size());

                if (dataRows.isEmpty() && headerRows.size() == 1) {
                    // 仅有一行（可能是空 sheet 或单行标题），保留整行
                    log.debug("Sheet '{}' 仅有表头，作为单块处理", sheetName);
                    chunks.add(buildSheetMarkdown(sheetName, headerRows, List.of(), fileName));
                    continue;
                }

                // 按阈值分块，每块都包含表头
                int totalChunks = (int) Math.ceil((double) dataRows.size() / CHUNK_THRESHOLD);
                for (int c = 0; c < totalChunks; c++) {
                    int from = c * CHUNK_THRESHOLD;
                    int to = Math.min(from + CHUNK_THRESHOLD, dataRows.size());
                    List<List<String>> chunkDataRows = dataRows.subList(from, to);

                    String chunkSheetName = totalChunks > 1
                            ? sheetName + " (" + (c + 1) + "/" + totalChunks + ")"
                            : sheetName;

                    chunks.add(buildSheetMarkdown(chunkSheetName, headerRows, chunkDataRows, fileName));
                }
            }
        } catch (org.apache.poi.EncryptedDocumentException e) {
            log.warn("Excel 文件已加密，跳过: {}", fileName);
            return List.of();
        } catch (Exception e) {
            log.warn("Excel 解析失败: {} - {}", fileName, e.getMessage());
            // TODO: 对于超大 Excel（>100MB），POI 可能存在内存溢出风险。
            //       后续可考虑使用 SXSSFWorkbook 流式处理或 XSSF Event API 逐行读取。
            if (fileSize > 100 * 1024 * 1024) {
                log.warn("Excel 文件过大 ({}MB)，可能影响解析性能: {}",
                        fileSize / (1024 * 1024), fileName);
            }
            throw new IOException("Excel 解析失败: " + fileName, e);
        }

        return chunks;
    }

    /**
     * 提取 Sheet 中的所有行数据（每行一个字符串列表）。
     * 跳过完全为空的行。
     */
    private List<List<String>> extractRows(Sheet sheet) {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : sheet) {
            List<String> cells = new ArrayList<>();
            boolean hasContent = false;

            for (Cell cell : row) {
                String value = getCellString(cell);
                cells.add(value);
                if (!value.isEmpty()) hasContent = true;
            }

            if (hasContent) {
                // 去掉末尾的空单元格
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                if (!cells.isEmpty()) {
                    rows.add(cells);
                }
            }
        }
        return rows;
    }

    /**
     * 将单元格值转换为字符串。
     * 特殊处理公式单元格（取公式计算结果）和富文本单元格。
     */
    private String getCellString(Cell cell) {
        if (cell == null) return "";

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                // 整数去小数点
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                // 先判断缓存结果类型，避免异常驱动的值获取
                CellType resultType = cell.getCachedFormulaResultType();
                yield switch (resultType) {
                    case NUMERIC -> {
                        double val = cell.getNumericCellValue();
                        if (val == Math.floor(val) && !Double.isInfinite(val)) {
                            yield String.valueOf((long) val);
                        }
                        yield String.valueOf(val);
                    }
                    case STRING -> cell.getStringCellValue().trim();
                    case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                    case ERROR -> {
                        byte errorCode = cell.getErrorCellValue();
                        yield "#ERR:" + errorCode;
                    }
                    case BLANK -> "";
                    default -> "";
                };
            }
            case BLANK -> "";
            default -> "";
        };
    }

    /**
     * 计算列宽：取表头和数据行中的最大列数，确保 Markdown 表格对齐。
     */
    private int calcColumnCount(List<List<String>> headerRows, List<List<String>> dataRows) {
        int max = 0;
        for (List<String> row : headerRows) {
            max = Math.max(max, row.size());
        }
        for (List<String> row : dataRows) {
            max = Math.max(max, row.size());
        }
        return max;
    }

    /**
     * 规范化一行数据到指定列数（不足的补空字符串）。
     */
    private List<String> normalizeRow(List<String> row, int colCount) {
        List<String> result = new ArrayList<>(row);
        while (result.size() < colCount) {
            result.add("");
        }
        return result;
    }

    /**
     * 构建 Sheet 的 Markdown 文本。
     *
     * @param sheetName   Sheet 名称（含分块序号，如有）
     * @param headerRows  表头行列表
     * @param dataRows    数据行列表
     * @param fileName    原始文件名
     * @return Markdown 格式文本
     */
    private String buildSheetMarkdown(String sheetName, List<List<String>> headerRows,
                                       List<List<String>> dataRows, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Sheet: ").append(sheetName).append("\n");
        sb.append("> 文件来源: ").append(fileName).append("\n\n");

        int colCount = calcColumnCount(headerRows, dataRows);

        // 输出表头
        for (List<String> headerRow : headerRows) {
            sb.append("| ");
            List<String> normalized = normalizeRow(headerRow, colCount);
            sb.append(String.join(" | ", normalized));
            sb.append(" |\n");
        }

        // 分隔线
        sb.append("|");
        for (int i = 0; i < colCount; i++) {
            sb.append("---|");
        }
        sb.append("\n");

        // 数据行
        for (List<String> dataRow : dataRows) {
            sb.append("| ");
            List<String> normalized = normalizeRow(dataRow, colCount);
            sb.append(String.join(" | ", normalized));
            sb.append(" |\n");
        }

        sb.append("\n");
        return sb.toString();
    }
}
