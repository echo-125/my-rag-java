package com.he.splitter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.NodeList;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JavaParser AST 的 Java 代码切分器。
 * 按类和方法边界切分，保留完整的方法签名和类结构信息。
 */
public class JavaAstDocumentSplitter implements DocumentSplitter {

    private static final String METADATA_PROJECT_NAME = "project_name";
    private static final String METADATA_FILE_PATH = "file_path";
    private static final String METADATA_LANGUAGE = "language";
    private static final String METADATA_TYPE = "type";
    private static final String METADATA_SIGNATURE = "signature";
    private static final String METADATA_START_LINE = "start_line";
    private static final String METADATA_END_LINE = "end_line";

    private final String projectName;
    private final String filePath;

    public JavaAstDocumentSplitter(String projectName, String filePath) {
        this.projectName = projectName;
        this.filePath = filePath;
    }

    @Override
    public List<TextSegment> split(Document document) {
        List<TextSegment> segments = new ArrayList<>();
        String sourceCode = document.text();

        try {
            CompilationUnit cu = StaticJavaParser.parse(sourceCode);

            // 切分类级别的代码块
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                if (clazz.isTopLevelType()) {
                    // 类声明（不含方法体，只保留字段、注解、类签名）
                    String classHeader = extractClassHeader(clazz);
                    segments.add(createSegment(classHeader, "class",
                            clazz.getNameAsString(),
                            clazz.getBegin().map(p -> p.line).orElse(0),
                            clazz.getEnd().map(p -> p.line).orElse(0)));

                    // 切分每个方法
                    clazz.getMethods().forEach(method -> {
                        String methodCode = method.toString();
                        String signature = extractMethodSignature(method);
                        segments.add(createSegment(methodCode, "method",
                                signature,
                                method.getBegin().map(p -> p.line).orElse(0),
                                method.getEnd().map(p -> p.line).orElse(0)));
                    });

                    // 切分构造函数
                    clazz.getConstructors().forEach(ctor -> {
                        String ctorCode = ctor.toString();
                        String signature = extractConstructorSignature(ctor);
                        segments.add(createSegment(ctorCode, "constructor",
                                signature,
                                ctor.getBegin().map(p -> p.line).orElse(0),
                                ctor.getEnd().map(p -> p.line).orElse(0)));
                    });
                }
            });

            // 如果没有找到类声明，按整个文件作为一个段
            if (segments.isEmpty()) {
                segments.add(createSegment(sourceCode, "file", filePath, 1,
                        sourceCode.split("\n").length));
            }

        } catch (Exception e) {
            // 解析失败时，作为纯文本处理
            segments.add(createSegment(sourceCode, "text", filePath, 1,
                    sourceCode.split("\n").length));
        }

        return segments;
    }

    private String extractClassHeader(ClassOrInterfaceDeclaration clazz) {
        // 提取类签名 + 字段声明（不含方法体）
        StringBuilder sb = new StringBuilder();
        // 包声明
        clazz.findCompilationUnit().ifPresent(cu ->
                cu.getPackageDeclaration().ifPresent(pkg ->
                        sb.append(pkg.toString()).append("\n")));
        // 导入
        clazz.findCompilationUnit().ifPresent(cu ->
                cu.getImports().forEach(imp -> sb.append(imp.toString()).append("\n")));
        // 类注解和签名
        clazz.getAnnotations().forEach(a -> sb.append(a.toString()).append("\n"));
        sb.append(clazz.getModifiers().toString()).append(" ");
        if (clazz.isInterface()) sb.append("interface ");
        else sb.append("class ");
        sb.append(clazz.getNameAsString());
        if (clazz.getExtendedTypes().isNonEmpty()) {
            sb.append(" extends ").append(clazz.getExtendedTypes());
        }
        if (clazz.getImplementedTypes().isNonEmpty()) {
            sb.append(" implements ").append(clazz.getImplementedTypes());
        }
        sb.append(" {\n");
        // 字段
        clazz.getFields().forEach(f -> sb.append("    ").append(f.toString()).append("\n"));
        sb.append("    // ... methods ...\n}");
        return sb.toString();
    }

    private String extractMethodSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder();
        method.getAnnotations().forEach(a -> sb.append(a.toString()).append(" "));
        sb.append(method.getModifiers().toString()).append(" ");
        sb.append(method.getTypeAsString()).append(" ");
        sb.append(method.getNameAsString()).append("(");
        NodeList<com.github.javaparser.ast.body.Parameter> params = method.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getTypeAsString()).append(" ").append(params.get(i).getNameAsString());
        }
        sb.append(")");
        return sb.toString();
    }

    private String extractConstructorSignature(com.github.javaparser.ast.body.ConstructorDeclaration ctor) {
        StringBuilder sb = new StringBuilder();
        sb.append(ctor.getNameAsString()).append("(");
        NodeList<com.github.javaparser.ast.body.Parameter> params = ctor.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getTypeAsString()).append(" ").append(params.get(i).getNameAsString());
        }
        sb.append(")");
        return sb.toString();
    }

    private TextSegment createSegment(String text, String type, String signature, int startLine, int endLine) {
        Metadata metadata = new Metadata();
        metadata.put(METADATA_PROJECT_NAME, projectName);
        metadata.put(METADATA_FILE_PATH, filePath);
        metadata.put(METADATA_LANGUAGE, "java");
        metadata.put(METADATA_TYPE, type);
        metadata.put(METADATA_SIGNATURE, signature);
        metadata.put(METADATA_START_LINE, String.valueOf(startLine));
        metadata.put(METADATA_END_LINE, String.valueOf(endLine));
        return TextSegment.from(text, metadata);
    }
}

