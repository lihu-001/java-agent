package dev.learnagent.s01;

import java.io.IOException;
import java.util.List;

@FunctionalInterface
public interface ModelClient {
    String complete(List<ChatMessage> messages) throws IOException, InterruptedException;
}
