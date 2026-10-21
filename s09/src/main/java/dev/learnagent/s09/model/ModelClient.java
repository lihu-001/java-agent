package dev.learnagent.s09.model;

import dev.learnagent.s09.tool.ToolDefinition;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
            throws IOException, InterruptedException;
}
