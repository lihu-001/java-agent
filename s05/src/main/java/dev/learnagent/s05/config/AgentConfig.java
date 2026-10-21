package dev.learnagent.s05.config;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record AgentConfig(String baseUrl, String apiKey, String model, Path workDirectory,
                          int maxModelCalls, Duration shellTimeout, int maxOutputChars) {
    public static AgentConfig load(Path path) {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            // 兼容 Windows 编辑器保存的 UTF-8 BOM，避免污染第一个配置项的名称。
            reader.mark(1);
            if (reader.read() != '\uFEFF') {
                reader.reset();
            }
            properties.load(reader);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("找不到 " + path.toAbsolutePath()
                    + "，请从 config.example.properties 复制并填写配置");
        } catch (IOException e) {
            throw new IllegalArgumentException("无法读取配置文件：" + path.toAbsolutePath());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("配置文件格式错误，请检查 properties 转义格式");
        }
        return new AgentConfig(
                required(properties, "openai.base-url", "https://api.openai.com/v1"),
                required(properties, "openai.api-key", null),
                required(properties, "openai.model", null),
                Path.of(required(properties, "agent.work-dir", "playground")),
                positiveInt(properties, "agent.max-model-calls", 10),
                Duration.ofSeconds(positiveInt(properties, "shell.timeout-seconds", 30)),
                positiveInt(properties, "shell.max-output-chars", 12000));
    }

    private static int positiveInt(Properties properties, String key, int defaultValue) {
        try {
            int value = Integer.parseInt(properties.getProperty(key, String.valueOf(defaultValue)).strip());
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 只展示配置名称，避免错误信息回显配置内容。
        }
        throw new IllegalArgumentException(key + " 必须为正整数");
    }

    private static String required(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key, defaultValue);
        if (StrUtil.isBlank(value)) {
            throw new IllegalArgumentException("请在 config.properties 中填写 " + key);
        }
        return value.strip();
    }

    @Override
    public String toString() {
        return "AgentConfig[baseUrl=" + baseUrl + ", apiKey=***, model=" + model + "]";
    }
}
