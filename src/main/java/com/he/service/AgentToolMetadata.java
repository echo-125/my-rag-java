package com.he.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 工具调用元数据收集器。
 * 使用 ThreadLocal 跟踪单次请求中的所有工具调用记录。
 */
public class AgentToolMetadata {

    public record ToolCallRecord(String toolName, String args, long durationMs) {}

    private static final ThreadLocal<List<ToolCallRecord>> CALLS = ThreadLocal.withInitial(ArrayList::new);

    public static void record(String toolName, String args, long durationMs) {
        CALLS.get().add(new ToolCallRecord(toolName, args, durationMs));
    }

    public static List<ToolCallRecord> collectAndClear() {
        List<ToolCallRecord> calls = new ArrayList<>(CALLS.get());
        CALLS.remove();
        return calls;
    }
}
