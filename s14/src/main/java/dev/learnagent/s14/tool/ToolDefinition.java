package dev.learnagent.s14.tool;

import com.alibaba.fastjson2.JSONObject;

public record ToolDefinition(String name, String description, JSONObject parameters) {
}
