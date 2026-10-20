package dev.learnagent.s03;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ToolPermission permission;

    public ToolRegistry(List<AgentTool> tools) {
        this(tools, call -> null);
    }

    public ToolRegistry(List<AgentTool> tools, ToolPermission permission) {
        this.permission = Objects.requireNonNull(permission);
        for (AgentTool tool : tools) {
            String name = tool.definition().name();
            if (name == null || name.isBlank() || this.tools.putIfAbsent(name, tool) != null) {
                throw new IllegalArgumentException("工具名称必须非空且不能重复");
            }
        }
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public String execute(ToolCall call) throws InterruptedException {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return "工具错误：未知工具 " + call.name();
        }
        // S03 在已有分派点增加检查；失败时不调用 execute，也不影响调用 ID 的回传。
        try {
            String reason = permission.denialReason(call);
            if (reason != null) {
                return "权限拒绝：" + reason + " 工具未执行。";
            }
        } catch (IOException | RuntimeException e) {
            return "权限拒绝：无法完成权限检查（" + e.getClass().getSimpleName() + "），工具未执行。";
        }
        try {
            return tool.execute(call.arguments());
        } catch (IllegalArgumentException e) {
            return "工具错误：" + e.getMessage();
        } catch (IOException e) {
            return "工具错误：文件或命令操作失败（" + e.getClass().getSimpleName() + "）。";
        }
    }
}
