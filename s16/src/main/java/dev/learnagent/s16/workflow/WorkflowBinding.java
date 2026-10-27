package dev.learnagent.s16.workflow;

import dev.learnagent.s16.tool.AgentTool;

import java.util.List;
import java.util.Objects;

/** 工作流绑定组装结果：tools 追加到主 Agent（团队模式为 Lead），rule 追加到系统提示词。 */
public record WorkflowBinding(List<AgentTool> tools, String rule) {

    public WorkflowBinding {
        tools = List.copyOf(tools);
        Objects.requireNonNull(rule);
    }
}
