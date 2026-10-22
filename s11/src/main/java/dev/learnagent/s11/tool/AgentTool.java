package dev.learnagent.s11.tool;

import java.io.IOException;

public interface AgentTool {
    ToolDefinition definition();

    String execute(String arguments) throws IOException, InterruptedException;
}
