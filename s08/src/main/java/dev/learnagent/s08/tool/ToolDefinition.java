package dev.learnagent.s08.tool;

import com.alibaba.fastjson2.JSONObject;

public record ToolDefinition(String name, String description, JSONObject parameters) {
}
