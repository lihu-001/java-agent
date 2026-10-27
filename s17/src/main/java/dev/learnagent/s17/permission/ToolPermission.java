package dev.learnagent.s17.permission;

import dev.learnagent.s17.tool.ToolCall;

import java.io.IOException;

@FunctionalInterface
public interface ToolPermission {
    /**
     * 执行前检查：返回 null 表示允许；非 null 表示拒绝原因。
     * 此方法不能执行工具本身。
     */
    String denialReason(ToolCall call) throws IOException, InterruptedException;
}
