package com.insolu.rag.splitter;

import org.apache.poi.xslf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PowerPoint (.pptx) 结构化解析器。
 * <p>
 * 使用 Apache POI 逐 Slide 遍历，提取标题、正文文本框、表格内容和演讲者备注，
 * 转换为格式良好的 Markdown 文本，解决 Tika 扁平化提取导致的文本框顺序混乱
 * 和备注丢失问题。
 * <p>
 * 提取顺序：标题 → 正文 → 表格 → 演讲者备注。
 */
public class PptxStructuredParser {

    private static final Logger log = LoggerFactory.getLogger(PptxStructuredParser.class);

    /**
     * 解析 PPT 文件，返回 Markdown 文本。
     * 每个元素对应一张幻灯片的 Markdown 表示。
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名
     * @return Markdown 文本列表，每个元素对应一张幻灯片
     * @throws IOException 文件读取或解析异常
     */
    public List<String> parse(byte[] fileBytes, String fileName) throws IOException {
        List<String> slides = new ArrayList<>();

        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(fileBytes))) {
            List<XSLFSlide> slideList = ppt.getSlides();
            int totalSlides = slideList.size();

            for (int i = 0; i < totalSlides; i++) {
                XSLFSlide slide = slideList.get(i);
                int pageNum = i + 1;

                StringBuilder sb = new StringBuilder();

                // 提取标题
                String title = extractTitle(slide);
                sb.append("# 幻灯片 ").append(pageNum);
                if (!title.isEmpty()) {
                    sb.append(": ").append(title);
                }
                sb.append("\n");

                // 提取正文文本框
                List<String> bodyTexts = extractBodyTexts(slide, title);
                if (!bodyTexts.isEmpty()) {
                    sb.append("## 正文\n\n");
                    for (String text : bodyTexts) {
                        sb.append(text).append("\n\n");
                    }
                }

                // 提取表格
                List<String> tables = extractTables(slide);
                if (!tables.isEmpty()) {
                    sb.append("## 表格内容\n\n");
                    for (String table : tables) {
                        sb.append(table).append("\n\n");
                    }
                }

                // 提取演讲者备注
                String notes = extractNotes(slide);
                if (!notes.isEmpty()) {
                    sb.append("## 演讲者备注\n\n");
                    sb.append(notes).append("\n\n");
                }

                String slideMarkdown = sb.toString().trim();
                if (!slideMarkdown.isEmpty()) {
                    slides.add(slideMarkdown);
                }
            }
        } catch (org.apache.poi.EncryptedDocumentException e) {
            log.warn("PPT 文件已加密，跳过: {}", fileName);
            return List.of();
        } catch (Exception e) {
            log.warn("PPT 解析失败: {} - {}", fileName, e.getMessage());
            // TODO: 对于含大量高清图片的 PPT，POI 可能存在内存风险。
            //       后续可考虑限制图片提取或使用流式处理。
            throw new IOException("PPT 解析失败: " + fileName, e);
        }

        return slides;
    }

    /**
     * 提取幻灯片标题。
     * 优先查找占位符类型为 TITLE 或 CENTERED_TITLE 的文本框，否则取第一个非空文本框。
     */
    private String extractTitle(XSLFSlide slide) {
        // 优先查找标题占位符
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String placeholderType = textShape.getPlaceholder() != null
                        ? textShape.getPlaceholder().toString()
                        : "";
                if (placeholderType.contains("TITLE") || placeholderType.contains("TITLE_TEXT")) {
                    String text = textShape.getText().trim();
                    if (!text.isEmpty()) return text;
                }
            }
        }
        // 降级：取第一个非空文本框
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                String text = textShape.getText().trim();
                if (!text.isEmpty()) return text;
            }
        }
        return "";
    }

    /**
     * 提取正文文本框（排除标题和表格中的文本）。
     * 保留列表层级（通过缩进表示）。
     */
    private List<String> extractBodyTexts(XSLFSlide slide, String title) {
        List<String> texts = new ArrayList<>();

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape textShape) {
                // 跳过标题
                String shapeText = textShape.getText().trim();
                if (shapeText.equals(title)) continue;

                // 跳过表格中的文本框（由 extractTables 处理）
                if (isInsideTable(shape)) continue;

                // 提取文本（保留层级）
                List<XSLFTextParagraph> paragraphs = textShape.getTextParagraphs();
                if (!paragraphs.isEmpty() && !shapeText.isEmpty()) {
                    StringBuilder paraBuilder = new StringBuilder();
                    for (XSLFTextParagraph para : paragraphs) {
                        String paraText = para.getText().trim();
                        if (paraText.isEmpty()) continue;

                        // 根据缩进级别添加列表标记
                        int indentLevel = para.getIndentLevel();
                        String prefix = "    ".repeat(Math.max(0, indentLevel - 1));
                        if (para.isBullet()) {
                            paraBuilder.append(prefix).append("- ").append(paraText).append("\n");
                        } else {
                            paraBuilder.append(prefix).append(paraText).append("\n");
                        }
                    }
                    String result = paraBuilder.toString().trim();
                    if (!result.isEmpty()) {
                        texts.add(result);
                    }
                }
            }
        }
        return texts;
    }

    /**
     * 提取幻灯片中的表格，转为 Markdown 表格格式。
     */
    private List<String> extractTables(XSLFSlide slide) {
        List<String> tables = new ArrayList<>();

        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTable table) {
                StringBuilder sb = new StringBuilder();
                int rowCount = table.getNumberOfRows();
                int colCount = table.getNumberOfColumns();

                for (int r = 0; r < rowCount; r++) {
                    sb.append("| ");
                    for (int c = 0; c < colCount; c++) {
                        XSLFTableCell cell = table.getCell(r, c);
                        if (cell == null) {
                            sb.append(" | ");
                            continue;
                        }
                        sb.append(cell.getText().trim().replace("\n", " "));
                        sb.append(" | ");
                    }
                    sb.append("\n");

                    // 第一行后添加分隔线
                    if (r == 0) {
                        sb.append("|");
                        for (int c = 0; c < colCount; c++) {
                            sb.append("---|");
                        }
                        sb.append("\n");
                    }
                }
                tables.add(sb.toString().trim());
            }
        }
        return tables;
    }

    /**
     * 提取演讲者备注。
     */
    private String extractNotes(XSLFSlide slide) {
        XSLFNotes notes = slide.getNotes();
        if (notes == null) return "";

        StringBuilder sb = new StringBuilder();
        for (XSLFTextShape shape : notes.getPlaceholders()) {
            String text = shape.getText().trim();
            if (!text.isEmpty()) {
                sb.append(text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /**
     * 判断文本框是否位于表格内。
     */
    private boolean isInsideTable(XSLFShape shape) {
        // 遍历父容器链，检查是否在 XSLFTable 内
        Object parent = shape.getParent();
        while (parent != null) {
            if (parent instanceof XSLFTable) return true;
            if (parent instanceof XSLFShape shapeParent) {
                parent = shapeParent.getParent();
            } else {
                break;
            }
        }
        return false;
    }
}
