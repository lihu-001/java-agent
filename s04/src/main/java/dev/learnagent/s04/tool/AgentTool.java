package dev.learnagent.s04.tool;

import java.io.IOException;

public interface AgentTool {
    ToolDefinition definition();

    String execute(String arguments) throws IOException, InterruptedException;
}
