package dev.learnagent.s01;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ChatLoop {
    private final ModelClient client;
    private final AgentTool tool;
    private final int maxModelCalls;

    public ChatLoop(ModelClient client) {
        this(client, new ShellTool(Path.of("playground"), Duration.ofSeconds(30), 12000), 10);
    }

    public ChatLoop(ModelClient client, AgentTool tool, int maxModelCalls) {
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("每轮模型调用上限必须为正数");
        }
        this.client = client;
        this.tool = tool;
        this.maxModelCalls = maxModelCalls;
    }

    public void run(BufferedReader input, PrintWriter output) throws IOException {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", "你是一个编程助手，请用中文回答。需要操作或查看环境时使用 shell 工具，"
                + "根据真实工具结果回答。工具错误可修正后再次调用，不要把失败描述为成功。"));
        List<ToolDefinition> definitions = List.of(tool.definition());
        output.println("对话已启动，输入 exit 或 quit 退出。每次输入可触发多次工具调用。");
        output.println("工具> " + tool.definition().description());
        while (true) {
            output.print("你> ");
            output.flush();
            String line = input.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.strip()) || "quit".equalsIgnoreCase(line.strip())) {
                output.println("对话已结束。");
                output.flush();
                return;
            }
            if (StrUtil.isBlank(line)) {
                continue;
            }

            // 首次请求失败仍丢弃本轮；工具执行后保留完整协议历史，操作不能靠丢弃消息撤销。
            List<ChatMessage> request = new ArrayList<>(history);
            request.add(new ChatMessage("user", line));
            boolean toolsRecorded = false;
            try {
                for (int step = 1; step <= maxModelCalls; step++) {
                    ChatMessage answer = client.complete(List.copyOf(request), definitions);
                    request.add(answer);
                    if (StrUtil.isNotBlank(answer.content())) {
                        output.println("助手> " + answer.content());
                    }
                    if (answer.toolCalls().isEmpty()) {
                        history = request;
                        break;
                    }
                    boolean limitReached = step == maxModelCalls;
                    for (ToolCall call : answer.toolCalls()) {
                        output.println("工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments());
                        output.flush();
                        String result = limitReached ? "工具未执行：已达到本轮模型调用上限。"
                                : executeTool(call);
                        request.add(ChatMessage.tool(call.id(), result));
                        output.println("工具结果> [" + call.id() + "] " + result);
                        output.flush();
                    }
                    history = request;
                    toolsRecorded = true;
                    if (limitReached) {
                        String reason = "已达到本轮模型调用上限（" + maxModelCalls + " 次），停止本轮；最后一批工具未执行。";
                        request.add(new ChatMessage("assistant", reason));
                        output.println("助手> " + reason);
                    }
                }
            } catch (HttpTimeoutException e) {
                output.println("请求超时；" + failureHint(toolsRecorded));
            } catch (IOException e) {
                output.println("请求失败：" + e.getMessage() + "；" + failureHint(toolsRecorded));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.println("请求已中断，对话结束。");
                output.flush();
                return;
            }
            output.flush();
        }
    }

    private String executeTool(ToolCall call) throws InterruptedException {
        if (!tool.definition().name().equals(call.name())) {
            return "工具错误：未知工具 " + call.name();
        }
        try {
            return tool.execute(call.arguments());
        } catch (IOException e) {
            return "工具错误：命令执行或输出读取失败（" + e.getClass().getSimpleName() + "）。";
        }
    }

    private String failureHint(boolean toolsRecorded) {
        return toolsRecorded ? "已保留工具调用和结果，已执行的操作不会回滚，请输入下一步。"
                : "本轮未保存，请重新输入。";
    }
}
