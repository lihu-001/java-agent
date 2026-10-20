package dev.learnagent.s02;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> tools) {
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
        try {
            return tool.execute(call.arguments());
        } catch (IllegalArgumentException e) {
            return "工具错误：" + e.getMessage();
        } catch (IOException e) {
            return "工具错误：文件或命令操作失败（" + e.getClass().getSimpleName() + "）。";
        }
    }
}
