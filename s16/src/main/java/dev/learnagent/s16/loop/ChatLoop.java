package dev.learnagent.s16.loop;

import dev.learnagent.s16.background.BackgroundManager;
import dev.learnagent.s16.compact.ContextCompactor;
import dev.learnagent.s16.harness.EventHub;
import dev.learnagent.s16.harness.ModelRetryPolicy;
import dev.learnagent.s16.harness.TurnEvents;
import dev.learnagent.s16.hook.AgentHooks;
import dev.learnagent.s16.model.ChatMessage;
import dev.learnagent.s16.model.ContextTooLargeException;
import dev.learnagent.s16.model.ModelClient;
import dev.learnagent.s16.model.ModelUnavailableException;
import dev.learnagent.s16.todo.TodoReminder;
import dev.learnagent.s16.tool.AgentTool;
import dev.learnagent.s16.tool.ShellTool;
import dev.learnagent.s16.tool.ToolCall;
import dev.learnagent.s16.tool.ToolDefinition;
import dev.learnagent.s16.tool.ToolRegistry;

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
    private final ContextCompactor compactor;
    private final BackgroundManager background;
    /** S15：程序事件中心与限流退避策略；为 null 时与 S14 行为一致。 */
    private final EventHub events;
    private final ModelRetryPolicy retryPolicy;

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
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, null);
    }

    /** S08：compactor 为 null 时与 S07 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, null);
    }

    /** S11：background 为 null 时与 S10 行为一致。 */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background) {
        this(client, registry, maxModelCalls, systemPrompt, hooks, reminder, compactor, background, null, null);
    }

    /**
     * S15：events 提供定时事件与哨兵轮状态，retry 提供限流退避；两者同时为 null 时与 S14 完全相同。
     * retry 为 null 而 events 非 null 时，限流不重试，只做事件唤醒与响应式压缩。
     */
    public ChatLoop(ModelClient client, ToolRegistry registry, int maxModelCalls, String systemPrompt,
                    AgentHooks hooks, TodoReminder reminder, ContextCompactor compactor,
                    BackgroundManager background, EventHub events, ModelRetryPolicy retryPolicy) {
        if (maxModelCalls <= 0) {
            throw new IllegalArgumentException("每轮模型调用上限必须为正数");
        }
        this.client = client;
        this.registry = registry;
        this.maxModelCalls = maxModelCalls;
        this.systemPrompt = systemPrompt;
        this.hooks = Objects.requireNonNull(hooks);
        this.reminder = reminder;
        this.compactor = compactor;
        this.background = background;
        this.events = events;
        this.retryPolicy = retryPolicy;
    }

    public void run(BufferedReader input, PrintWriter output) throws IOException {
        try {
            converse(input, output);
        } finally {
            // S11：退出、EOF、中断或异常结束会话时都取消仍在运行的后台命令。
            if (background != null) {
                int cancelled = background.shutdown();
                if (cancelled > 0) {
                    output.println("后台> 会话结束，已取消 " + cancelled + " 个仍在运行的后台任务。");
                    output.flush();
                }
            }
        }
    }

    private void converse(BufferedReader input, PrintWriter output) throws IOException {
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
            // S15：程序事件的哨兵行不是用户输入，不记录消息、不触发 UserPromptSubmit。
            boolean synthetic = events != null && TurnEvents.isWake(line);
            if (StrUtil.isBlank(line)) {
                continue;
            }

            // 首次请求失败仍丢弃本轮；工具执行后保留完整协议历史，操作不能靠丢弃消息撤销。
            List<ChatMessage> request = new ArrayList<>(history);
            int turnStart = request.size();
            boolean toolsRecorded = false;
            // S11：本轮未保存前交付的通知；首次请求失败、本轮被丢弃时放回队列。
            List<String> unsavedScheduled = new ArrayList<>();
            List<String> unsavedBackground = new ArrayList<>();
            try {
                if (!synthetic) {
                    // S04：过滤退出/空输入后，先触发钩子，再记录用户消息。
                    String blocked = hooks.userPromptSubmit(line, history);
                    if (blocked != null) {
                        output.println("钩子拒绝：" + blocked + " 提示词未记录，未请求模型。");
                        output.flush();
                        continue;
                    }
                    request.add(new ChatMessage("user", line));
                }
                if (reminder != null) {
                    reminder.startTurn();
                }
                // S15：哨兵轮标记给 PermissionGate，程序自动发起的轮次拒绝交互确认。
                if (events != null) {
                    events.setSyntheticTurn(synthetic);
                }
                try {
                    for (int step = 1; step <= maxModelCalls; step++) {
                        // S15：定时事件与后台完成通知合并附加到最后一条 user/tool 消息末尾，不新增消息角色。
                        List<String> scheduled = events == null ? List.of() : events.drain();
                        List<String> notices = background == null ? List.of() : background.takeNotifications();
                        if (scheduled.isEmpty() && notices.isEmpty()) {
                            if (synthetic && !toolsRecorded) {
                                // 唤醒后事件已被取走：无事可做，回到等待输入，不空请求模型。
                                break;
                            }
                        } else {
                            if (!toolsRecorded) {
                                unsavedScheduled.addAll(scheduled);
                                unsavedBackground.addAll(notices);
                            }
                            if (synthetic) {
                                // 哨兵轮没有本轮的 user/tool 消息，事件作为一条新的用户消息进入。
                                request.add(new ChatMessage("user", renderNotices(scheduled, notices)));
                            } else {
                                appendNotices(request, scheduled, notices);
                            }
                            output.println(scheduled.isEmpty() ? "后台> 已将完成结果交给模型"
                                    : "事件> 已将程序事件交给模型");
                            output.flush();
                        }
                        // 非流式请求在完整响应前没有输出，先提示等待并在返回后报告耗时。
                        output.println("模型请求> 第 " + step + " 次请求中...");
                        output.flush();
                        List<ChatMessage> sent = request;
                        if (compactor != null) {
                            // S08：请求前整理上下文；总结后本轮之前的内容都在摘要里，本轮从摘要消息算起。
                            ContextCompactor.Prepared prepared = compactor.beforeRequest(List.copyOf(request), output);
                            if (prepared.summarized()) {
                                request = new ArrayList<>(prepared.history());
                                turnStart = request.size() - 1;
                                if (toolsRecorded) {
                                    history = request;
                                }
                            }
                            sent = prepared.sent();
                        }
                        long startNanos = System.nanoTime();
                        Recovery recovery = completeWithRecovery(sent, request, definitions, output);
                        if (recovery.summarized()) {
                            // 响应式压缩后的历史成为新的对话状态，本轮从摘要消息算起。
                            request = new ArrayList<>(recovery.history());
                            turnStart = request.size() - 1;
                            if (toolsRecorded) {
                                history = request;
                            }
                            sent = recovery.sent();
                        }
                        ChatMessage answer = recovery.answer();
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
                } finally {
                    if (events != null) {
                        events.setSyntheticTurn(false);
                    }
                }
            } catch (HttpTimeoutException e) {
                requeue(toolsRecorded, unsavedScheduled, unsavedBackground);
                output.println("请求超时；" + failureHint(toolsRecorded));
            } catch (IOException e) {
                requeue(toolsRecorded, unsavedScheduled, unsavedBackground);
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

    /**
     * S15：限流（HTTP 429/503/529）按 Retry-After 或指数退避重试，超过次数上限后按普通失败处理；
     * 上下文超长先响应式压缩一次再重试，压缩不出摘要时按普通失败处理。其余失败原样抛出。
     * summarized 为 true 表示对话历史已被替换为摘要，调用方需要采纳新的历史。
     */
    private Recovery completeWithRecovery(List<ChatMessage> sent, List<ChatMessage> request,
                                          List<ToolDefinition> definitions, PrintWriter output)
            throws IOException, InterruptedException {
        int attempt = 0;
        boolean reactiveCompacted = false;
        while (true) {
            try {
                return new Recovery(client.complete(List.copyOf(sent), definitions), request, sent, false);
            } catch (ModelUnavailableException e) {
                attempt++;
                if (retryPolicy == null || attempt > retryPolicy.maxRetries()) {
                    throw e;
                }
                long delay = retryPolicy.delayMillis(attempt, e.retryAfterSeconds());
                output.println("模型请求> " + e.getMessage() + "，等待 " + (delay / 1000)
                        + " 秒后第 " + attempt + " 次重试...");
                output.flush();
                retryPolicy.sleep(delay);
            } catch (ContextTooLargeException e) {
                if (compactor == null || reactiveCompacted) {
                    throw e;
                }
                output.println("压缩> " + e.getMessage() + "，保存完整记录并请求模型总结...");
                output.flush();
                // 响应式压缩作用于对话历史本身，而不是只影响本次发送的微压缩视图。
                ContextCompactor.Prepared prepared = compactor.beforeRequest(request, output, true);
                if (!prepared.summarized()) {
                    throw e;
                }
                sent = prepared.sent();
                request = prepared.history();
                reactiveCompacted = true;
            }
        }
    }

    /** 一次模型请求的结果；summarized 时 history/sent 是压缩后的新状态。 */
    private record Recovery(ChatMessage answer, List<ChatMessage> history, List<ChatMessage> sent,
                            boolean summarized) {
    }

    /** 程序事件与后台完成合并渲染，定时事件在前。 */
    private static String renderNotices(List<String> scheduled, List<String> notices) {
        StringBuilder extra = new StringBuilder();
        if (!scheduled.isEmpty()) {
            extra.append(EventHub.render(scheduled));
        }
        if (!notices.isEmpty()) {
            if (extra.length() > 0) {
                extra.append("\n\n");
            }
            extra.append(BackgroundManager.render(notices));
        }
        return extra.toString();
    }

    /** 程序事件与后台完成合并附加到最后一条消息末尾，定时事件在前；消息没有文本时直接以事件开头。 */
    private static void appendNotices(List<ChatMessage> request, List<String> scheduled, List<String> notices) {
        String extra = renderNotices(scheduled, notices);
        int last = request.size() - 1;
        ChatMessage message = request.get(last);
        String base = message.content() == null ? "" : message.content();
        String content = base.isEmpty() ? extra : base + "\n\n" + extra;
        request.set(last, "tool".equals(message.role())
                ? ChatMessage.tool(message.toolCallId(), content)
                : new ChatMessage(message.role(), content));
    }

    private void requeue(boolean toolsRecorded, List<String> unsavedScheduled, List<String> unsavedBackground) {
        if (toolsRecorded) {
            return;
        }
        if (!unsavedScheduled.isEmpty() && events != null) {
            events.requeue(unsavedScheduled);
        }
        if (!unsavedBackground.isEmpty() && background != null) {
            background.requeue(unsavedBackground);
        }
    }

    private String failureHint(boolean toolsRecorded) {
        return toolsRecorded ? "已保留工具调用和结果，已执行的操作不会回滚，请输入下一步。"
                : "本轮未保存，请重新输入。";
    }
}
