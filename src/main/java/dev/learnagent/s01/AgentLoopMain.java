package dev.learnagent.s01;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

public final class AgentLoopMain {
    private AgentLoopMain() {
    }

    public static void main(String[] args) {
        // 交互终端沿用控制台编码；重定向输入输出明确使用 UTF-8。
        Console console = System.console();
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        try {
            AgentConfig config = AgentConfig.load(Path.of("config.properties"));
            ModelClient client = new OpenAiClient(config.baseUrl(), config.apiKey(),
                    config.model(), Duration.ofSeconds(60));
            AgentTool shell = new ShellTool(config.workDirectory(), config.shellTimeout(), config.maxOutputChars());
            new ChatLoop(client, shell, config.maxModelCalls()).run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("终端读取失败，对话结束。");
        } finally {
            output.flush();
        }
    }
}
