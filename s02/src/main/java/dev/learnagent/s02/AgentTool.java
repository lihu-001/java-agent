package dev.learnagent.s02;

import java.io.IOException;

public interface AgentTool {
    ToolDefinition definition();

    String execute(String arguments) throws IOException, InterruptedException;
}
