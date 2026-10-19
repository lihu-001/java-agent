package dev.learnagent.s01;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    private HttpServer server;
    private String baseUrl;
    private int status = 200;
    private String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"你好，小李\"}}]}";
    private long delayMillis;
    private final AtomicReference<JSONObject> request = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                method.set(exchange.getRequestMethod());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                request.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                if (delayMillis > 0) {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void sendsChatCompletionWithUtf8HistoryAndNoTools() throws Exception {
        String answer = client(baseUrl + "/", Duration.ofSeconds(5)).complete(List.of(
                new ChatMessage("system", "请用中文回答"), new ChatMessage("user", "你好")));

        assertEquals("你好，小李", answer);
        assertEquals("POST", method.get());
        assertEquals("Bearer test-key", authorization.get());
        assertEquals("test-model", request.get().getString("model"));
        assertFalse(request.get().getBooleanValue("stream"));
        assertFalse(request.get().containsKey("tools"));
        assertEquals("你好", request.get().getJSONArray("messages").getJSONObject(1).getString("content"));
    }

    @Test
    void httpErrorIncludesStatusButDoesNotEchoProviderBody() {
        status = 401;
        response = "private provider details test-key";
        IOException error = assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of()));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("test-key"));
        assertFalse(error.getMessage().contains("private"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":{}}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{}]}}]}"})
    void rejectsMissingOrMalformedTextResponses(String body) {
        response = body;
        assertThrows(IOException.class, () -> client(baseUrl, Duration.ofSeconds(5)).complete(List.of()));
    }

    @Test
    void requestHasTimeout() {
        delayMillis = 500;
        assertThrows(HttpTimeoutException.class,
                () -> client(baseUrl, Duration.ofMillis(100)).complete(List.of()));
    }

    private OpenAiClient client(String url, Duration timeout) {
        return new OpenAiClient(url, "test-key", "test-model", timeout);
    }
}
