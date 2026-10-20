package dev.learnagent.s01;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public final class OpenAiClient implements ModelClient {
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public OpenAiClient(String baseUrl, String apiKey, String model, Duration timeout) {
        if (StrUtil.isBlank(baseUrl) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(model)) {
            throw new IllegalArgumentException("openai.base-url、openai.api-key 和 openai.model 不能为空");
        }
        URI base;
        try {
            base = URI.create(baseUrl.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("openai.base-url 格式无效");
        }
        if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("openai.base-url 必须是 HTTP(S) 基础地址，不含认证信息、查询参数或片段");
        }
        this.endpoint = URI.create(base.toString().replaceAll("/+$", "") + "/chat/completions");
        this.apiKey = apiKey.strip();
        if (this.apiKey.contains("\r") || this.apiKey.contains("\n")) {
            throw new IllegalArgumentException("openai.api-key 不能包含换行");
        }
        this.model = model.strip();
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String complete(List<ChatMessage> messages) throws IOException, InterruptedException {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 服务商错误正文可能包含敏感信息，仅报告状态码。
            throw new IOException("模型接口返回 HTTP " + response.statusCode());
        }
        return parseAnswer(response.body());
    }

    private String parseAnswer(String body) throws IOException {
        try {
            JSONObject response = JSON.parseObject(body);
            JSONArray choices = response == null ? null : response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                throw new IOException("模型响应缺少 choices");
            }
            JSONObject choice = choices.getJSONObject(0);
            String finishReason = choice.getString("finish_reason");
            if ("length".equals(finishReason) || "content_filter".equals(finishReason)) {
                throw new IOException("模型响应未完成：" + finishReason);
            }
            JSONObject message = choice.getJSONObject("message");
            if (message == null || !"assistant".equals(message.getString("role"))) {
                throw new IOException("模型响应缺少 assistant 消息");
            }
            JSONArray calls = message.getJSONArray("tool_calls");
            if ((calls != null && !calls.isEmpty()) || message.get("function_call") != null) {
                throw new IOException("当前版本只支持文本回答");
            }
            if (!(message.get("content") instanceof String answer) || StrUtil.isBlank(answer)) {
                throw new IOException("模型响应缺少文本 content");
            }
            return answer;
        } catch (RuntimeException e) {
            throw new IOException("模型响应不是有效的 Chat Completions JSON");
        }
    }
}
