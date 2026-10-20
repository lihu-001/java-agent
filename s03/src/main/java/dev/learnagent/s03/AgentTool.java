package dev.learnagent.s03;

import java.io.IOException;

public interface AgentTool {
    ToolDefinition definition();

    String execute(String arguments) throws IOException, InterruptedException;
}
