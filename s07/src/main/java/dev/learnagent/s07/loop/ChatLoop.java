package dev.learnagent.s07.loop;

import dev.learnagent.s07.hook.AgentHooks;
import dev.learnagent.s07.model.ChatMessage;
import dev.learnagent.s07.model.ModelClient;
import dev.learnagent.s07.todo.TodoReminder;
import dev.learnagent.s07.tool.AgentTool;
import dev.learnagent.s07.tool.ShellTool;
import dev.learnagent.s07.tool.ToolCall;
import dev.learnagent.s07.tool.ToolDefinition;
import dev.learnagent.s07.tool.ToolRegistry;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChatLoop {
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个编程助手，请用中文回答。需要操作或查看环境时使用 shell 工具，"
            + "根据真实工具结果回答。工具错误可修正后再次调用，不要把失败描述为成功。";
    private final ModelClient client;
    private final ToolRegistry registry;
    private final int maxModelCalls;
    private final String systemPrompt;
    private final AgentHooks hooks;
    private final TodoReminder reminder;

    public ChatLoop(ModelClient client) {
        this(client, new ShellTool(Path.of("playground"), Duration.ofSeconds(30), 12000), 10);
    }

    public ChatLoop(ModelClient client, AgentTool tool, int maxModelCalls) {
        this(client, new ToolRegistry(List.of(tool)), maxModelCalls, DEFAULT_SYSTEM_PROMPT);
    }

    // 支持单工具练习，也可注入多个工具及系统提示词。
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt) {
        this(client, registry, maxModelCalls, systemPrompt, AgentHooks.empty());
    }

    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls,
                    String systemPrompt, AgentHooks hooks) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, null);
    }

    /** S05：reminder 为 null 时与 S04 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls,
                    String systemPrompt, AgentHooks hooks, TodoReminder reminder) {
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("每轮模型调用上限必须为正数");
        }
        this.client = client;
        this.registry = registry;
        this.maxModelCalls = maxModelCalls;
        this.systemPrompt = systemPrompt;
        this.hooks = Objects.requireNonNull(hooks);
        this.reminder = reminder;
    }

    public void run(BufferedReader input, PrintWriter output) throws IOException {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", systemPrompt));
        List<ToolDefinition> definitions = registry.definitions();
        output.println("对话已启动，输入 exit 或 quit 退出。每次输入可触发多次工具调用。");
        for (ToolDefinition definition : definitions) {
            output.println("工具> " + definition.name() + "：" + definition.description());
        }
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
            int turnStart = request.size();
            boolean toolsRecorded = false;
            try {
                // S04：过滤退出/空输入后，先触发钩子，再记录用户消息。
                String blocked = hooks.userPromptSubmit(line, history);
                if (blocked != null) {
                    output.println("钩子拒绝：" + blocked + " 提示词未记录，未请求模型。");
                    output.flush();
                    continue;
                }
                request.add(new ChatMessage("user", line));
                if (reminder != null) {
                    reminder.startTurn();
                }
                for (int step = 1; step <= maxModelCalls; step++) {
                    // 非流式请求在完整响应前没有输出，先提示等待并在返回后报告耗时。
                    output.println("模型请求> 第 " + step + " 次请求中...");
                    output.flush();
                    long startNanos = System.nanoTime();
                    ChatMessage answer = client.complete(List.copyOf(request), definitions);
                    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
                    output.println("模型响应> 耗时 " + elapsedMillis + " ms，工具调用 "
                            + answer.toolCalls().size() + " 个");
                    output.flush();
                    request.add(answer);
                    if (StrUtil.isNotBlank(answer.content())) {
                        output.println("助手> " + answer.content());
                    }
                    if (answer.toolCalls().isEmpty()) {
                        history = request;
                        output.flush();
                        hooks.stop(request.subList(turnStart, request.size()));
                        break;
                    }
                    boolean limitReached = step == maxModelCalls;
                    for (ToolCall call : answer.toolCalls()) {
                        output.println("工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments());
                        output.flush();
                        String result = limitReached ? "工具未执行：已达到本轮模型调用上限。"
                                : registry.execute(call);
                        request.add(ChatMessage.tool(call.id(), result));
                        output.println("工具结果> [" + call.id() + "] " + result);
                        output.flush();
                    }
                    // S05：提醒附加在本批最后一个工具结果后，不新增消息角色，协议顺序不变。
                    String nudge = reminder == null || limitReached ? null : reminder.afterToolBatch();
                    if (nudge != null) {
                        int last = request.size() - 1;
                        ChatMessage tool = request.get(last);
                        request.set(last, ChatMessage.tool(tool.toolCallId(), tool.content() + "\n\n" + nudge));
                        output.println("提醒> " + nudge);
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

    private String failureHint(boolean toolsRecorded) {
        return toolsRecorded ? "已保留工具调用和结果，已执行的操作不会回滚，请输入下一步。"
                : "本轮未保存，请重新输入。";
    }
}
