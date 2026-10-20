package dev.learnagent.s03;

import com.alibaba.fastjson2.JSONObject;

public record ToolDefinition(String name, String description, JSONObject parameters) {
}
