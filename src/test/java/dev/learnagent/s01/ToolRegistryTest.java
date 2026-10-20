package dev.learnagent.s01;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {
    @Test
    void preservesRegistrationOrderAndDispatchesByExactName() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(tool("a"), tool("b")));
        assertEquals(List.of("a", "b"), registry.definitions().stream().map(ToolDefinition::name).toList());
        assertEquals("b:{}", registry.execute(new ToolCall("id", "b", "{}")));
        assertTrue(registry.execute(new ToolCall("id", "B", "{}")).contains("未知工具"));
        assertThrows(UnsupportedOperationException.class, () -> registry.definitions().clear());
    }

    @Test
    void rejectsDuplicateAndBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool("a"), tool("a"))));
        assertThrows(IllegalArgumentException.class, () -> new ToolRegistry(List.of(tool(" "))));
    }

    @Test
    void returnsIoErrorsWithoutLeakingDetailsAndPropagatesInterruptions() throws Exception {
        AgentTool failing = new AgentTool() {
            public ToolDefinition definition() {
                return tool("broken").definition();
            }

            public String execute(String arguments) throws IOException, InterruptedException {
                if ("interrupt".equals(arguments)) {
                    throw new InterruptedException();
                }
                throw new IOException("private path");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(failing));
        String error = registry.execute(new ToolCall("id", "broken", "{}"));
        assertTrue(error.contains("IOException"));
        assertFalse(error.contains("private"));
        assertThrows(InterruptedException.class,
                () -> registry.execute(new ToolCall("id", "broken", "interrupt")));
    }

    private AgentTool tool(String name) {
        return new AgentTool() {
            public ToolDefinition definition() {
                return new ToolDefinition(name, name, new JSONObject());
            }

            public String execute(String arguments) {
                return name + ":" + arguments;
            }
        };
    }
}
