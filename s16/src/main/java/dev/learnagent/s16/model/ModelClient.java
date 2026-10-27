package dev.learnagent.s16.model;

import dev.learnagent.s16.tool.ToolDefinition;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    ChatMessage complete(List<ChatMessage> messages, List<ToolDefinition> tools)
            throws IOException, InterruptedException;
}
