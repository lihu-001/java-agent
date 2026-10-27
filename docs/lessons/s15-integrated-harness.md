# S15：Integrated Harness 集成运行时

本课在独立 s15 子工程中，以 S14 完整源码为起点做集成。入口为 `dev.learnagent.s15.HarnessMain`，S14 的 `McpMain` 移入 `app`。本课**不新增独立机制**：不添加新工具、新存储目录或新协议，只做两件事——把 S01—S14 的机制整合到同一条请求管线上，并补上机制组合时才暴露的缺口。

## 1. 为什么需要一节集成课

前十四课每课证明的都是“本课机制单独能跑”：S11 的后台通知、S12 的定时结果、S13 的队友消息各自有自己的送达路径；S01 定下的“失败不重试”在后十四课从未被重新审视。单机制视角看不到的问题：

| 组合场景 | 单课视角 | 组合时 |
| --- | --- | --- |
| 定时到点、后台完成同时到达 | 各自正确 | 合并顺序、只送达一次？ |
| 程序事件发起的轮次请求确认 | 确认流程正确 | 用户不在终端，永久阻塞 |
| 服务商限流（429） | 失败即结束本轮 | 长任务随机中断 |
| 上下文超长错误 | 压缩只按阈值触发 | 错误发生后没人负责恢复 |

集成课的产出是把每一轮请求固化成一条明确的管线，并用组合回归测试钉住它：

```text
输入来源：用户输入 / 程序事件（定时终态、后台完成）
→ UserPromptSubmit Hook（仅用户输入）
→ 取用并合并程序事件（定时 <system-events> 在前，后台 <background-results> 在后）
→ 上下文压缩（阈值自动 / compact 登记）
→ 请求模型（限流退避重试；上下文超长先响应式压缩）
→ 无工具调用：Stop Hook，本轮结束
→ 有工具调用：PreToolUse → 权限（哨兵轮 ASK 自动拒绝）→ 执行 → PostToolUse → 回传 → 下一轮
```

对照代码：`loop/ChatLoop` 的 `converse` 就是这条管线的实现，从上到下逐段可读。

## 2. 事件唤醒：程序也能发起一轮

S14 之前，循环只由用户输入和队友消息驱动。后台通知要等下一次输入才送达；定时结果只写回计划文件，模型永远不知道定时任务干完了什么。S15 让程序事件也能发起一轮，分三步。

**第一步：定时终态进对话。** `ScheduleManager` 新增带事件出口的构造方法：

```java
// completed() 末尾：取消只在会话结束时发生，不需要送达对话；其余终态交给事件中心。
if (scheduledEvents != null && status != ScheduleRunStatus.CANCELLED) {
    scheduledEvents.accept("[" + schedule.id() + "（" + schedule.name() + "）] "
            + status.wireName() + "，耗时 " + completion.elapsedMillis() + " ms\n" + output.strip());
}
```

这是一次明确的边界修改：S12 的规则是“结果不进对话”，理由是避免重复计划反复打断对话。S15 用两个限定保住了这个理由——取消（会话结束时发生）不送达，送达内容截断到 2000 字符。上一课入口 `McpMain` 不传事件出口，行为不变。

**第二步：唤醒空闲的循环。** 难点在 I/O：`System.in` 上的阻塞 `readLine` 无法被其他线程唤醒。S13 的 `TeamConsole` 已经解决了这个问题——守护线程专职把终端行搬进队列，`readLine` 从队列取。S15 把同一模式泛化：

- 单 Agent 用新类 `harness.AgentConsole`（结构比 `TeamConsole` 简单：只有 Line / End / Wake 三种事件，没有确认请求）；
- 团队模式复用 `TeamConsole`，新增 `prompts(mail, hub)` 重载：程序事件到达时唤醒 Lead，但**队友消息优先**；
- 两个控制台都返回同一个哨兵行 `TurnEvents.WAKE_LINE`（即 `"<system-events>"`）。

为什么用哨兵行而不是直接把事件文本当作输入返回？因为**送达点必须只有一处**。事件由 `ChatLoop` 在请求前从 `EventHub` 和 `BackgroundManager` 各自的队列取走、合并渲染、放回——放回、去重、渲染格式的逻辑都集中在循环里。控制台只负责“叫人”，不搬运内容。

**第三步：哨兵轮语义。** `ChatLoop` 识别到哨兵行后，这一轮与用户轮有四点不同：

```java
// S15：程序事件的哨兵行不是用户输入，不记录消息、不触发 UserPromptSubmit。
boolean synthetic = events != null && TurnEvents.isWake(line);
...
if (synthetic) {
    // 哨兵轮没有本轮的 user/tool 消息，事件作为一条新的用户消息进入。
    request.add(new ChatMessage("user", renderNotices(scheduled, notices)));
}
```

1. 不记录用户消息、不触发 `UserPromptSubmit`（它本来就不是提示词）；
2. 事件合并渲染成一条新 user 消息（`<system-events>…</system-events>` 在前、`<background-results>…</background-results>` 在后）；
3. 事件已被取走（比如上一轮刚消费过）时直接回到等待，**不空请求模型**；
4. 轮次期间 `EventHub.setSyntheticTurn(true)`，`PermissionGate` 对 ASK 立即拒绝：

```java
if (asyncTurn != null && asyncTurn.getAsBoolean()) {
    return "本消息由程序事件自动发起，用户可能不在终端旁，交互确认被拒绝；"
            + "需要执行时请在用户回来后请求批准。";
}
```

这是上游 s15 的经验：程序发起的轮次不能阻塞在终端确认上，否则一个深夜定时触发的轮次会让程序挂到天亮。队友的确认不受影响——它们的 ASK 排队交主线程，是 S13 的设计特性。

**唤醒与入队的时序。** 一个真实发生过的竞态：`BackgroundManager.finish` 原来先触发完成监听器再入队通知，唤醒的循环醒来时队列还是空的。修复是把顺序反过来——先 `notifications.add` 再触发监听器，保证醒来一定取得到。空事件的守卫（第 3 点）是第二道保险。

## 3. 错误恢复：两类可恢复的失败

S01 的“失败不重试”是教学边界。真实的编程 Agent 至少要能恢复两类错误，`OpenAiClient` 把它们从普通 `IOException` 中分离出来：

```java
if (status == 429 || status == 503 || status == 529) {
    return new ModelUnavailableException("模型接口返回 HTTP " + status
            + (status == 429 ? "（限流）" : "（服务暂不可用）"), retryAfterSeconds(response));
}
if ((status == 400 || status == 413) && looksLikeContextOverflow(response.body())) {
    return new ContextTooLargeException("模型接口返回 HTTP " + status + "（上下文超长）");
}
```

**限流退避**（`harness.ModelRetryPolicy`）：优先用服务商的 `Retry-After`（只解析数字秒，上限 60 秒），否则指数退避 2s → 4s → 8s（上限 30 秒），默认最多重试 3 次。`Sleeper` 接口可注入，测试记录延迟而不真正睡眠：

```java
public long delayMillis(int attempt, Long retryAfterSeconds) {
    if (retryAfterSeconds != null && retryAfterSeconds > 0) {
        return Math.min(retryAfterSeconds * 1000L, MAX_RETRY_AFTER_MILLIS);
    }
    long delay = attempt <= 1 ? baseDelayMillis : baseDelayMillis * (1L << Math.min(attempt - 1, 20));
    return Math.min(delay, MAX_BACKOFF_MILLIS);
}
```

**响应式压缩**：S08 的压缩只在超过阈值或模型调用 `compact` 时触发。收到上下文超长错误时，等阈值已经太晚——`ContextCompactor.beforeRequest` 增加 `force` 重载，不看阈值立即总结一次并重试。一个实现细节值得注意：`completeWithRecovery` 返回的不是 `ChatMessage` 而是一个 `Recovery` 记录，压缩发生时把新的历史一起带回，由调用方采纳：

```java
if (recovery.summarized()) {
    // 响应式压缩后的历史成为新的对话状态，本轮从摘要消息算起。
    request = new ArrayList<>(recovery.history());
    turnStart = request.size() - 1;
    if (toolsRecorded) {
        history = request;
    }
    sent = recovery.sent();
}
```

如果压缩发生在辅助方法内部而不让调用方知道，外层的历史就和新发送的内容脱节，`turnStart`、Stop 事件的快照全部错位——这是本课实现中实际出现过又改掉的写法。

两类恢复都有兜底：重试耗尽、压缩不出摘要时，回退到 S14 的失败路径（报错结束本轮；未保存时事件按原队列放回，下次再交付，每条只送达一次）。另外一条不变的纪律是**错误正文不进异常消息**——服务商的错误响应可能包含敏感信息，分类只依据状态码与超长文案的关键词匹配。

## 4. 装配：一个开关，三个入口

`harness.HarnessOptions(events, retry, loopInput)` 是集成的总开关。`options == null` 时所有增强关闭：

| 入口 | options | 行为 |
| --- | --- | --- |
| `HarnessMain`（默认） | 有 | 全部增强 |
| `app.McpMain`（S14 入口） | 无 | 与 S14 完全相同 |
| `app.ScheduleMain` / `app.TeamMain` 新重载 | 按调用者 | 供嵌入与测试 |

“完全相同”必须逐字成立，包括系统提示词。定时终态送达是新能力，直接改 `SCHEDULE_RULE` 会让上一课入口的提示词也变——所以新增 `SCHEDULE_EVENT_RULE`，只在 options 启用时追加：

```java
/** S15：事件唤醒可用时附加终态送达说明。 */
public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries, boolean scheduledEvents) {
    return systemPrompt(catalog, entries) + (scheduledEvents ? "\n\n" + SCHEDULE_EVENT_RULE : "");
}
```

这和前十四课“保留上一课入口便于对照”的原则一致：对照的前提是行为真的相同。

## 5. 容易出错的地方

- **唤醒先于入队**。监听器触发前必须先让结果可被取到（见第 2 节的时序修复）；空事件守卫是竞态的第二道防线。
- **提示打印的顺序**。`模型请求> 第 N 次请求中...` 要在事件取用之后打印，否则空哨兵轮也会打印“请求中”然后悄悄放弃，日志与行为矛盾——全量测试抓到过。
- **放回要回原队列**。定时事件放回 `EventHub`，后台通知放回 `BackgroundManager`，混放会导致重渲染时标签错乱（后台通知被包进 `<system-events>`）。
- **压缩结果必须被外层采纳**。见第 3 节的 `Recovery` 记录。
- **提示词一致性靠结构保证**。新规则挂在 options 分支上，而不是靠上一课入口“恰好不传”。
- **重复唤醒无害化**。哨兵行进入 `ChatLoop` 后先取事件再决定是否请求模型，多次唤醒、事件已被消费都只是多一次空轮，不会多一次模型调用。

## 6. 没有实现的部分

- max_tokens 截断升级（`finish_reason=length` 时提高上限重试）；
- 上游的动态工具池（每轮重组）与 `connect_mcp` 运行时连接——MCP 仍只在启动时经信任确认接入（S14）;
- 权限下沉为 PreToolUse Hook（上游做法；本课保持 S03 的分派前检查架构）；
- 限流重试不区分错误类型（配额耗尽与瞬时限流同策略）。

## 7. 练习

1. **定时唤醒**：启动 `HarnessMain`，让模型 `schedule_create` 一条 `* * * * *`、命令为 `echo 阶段检查完成` 的计划，然后**不再输入任何内容**。到点观察：`计划> … 运行结束` → `事件> 已将程序事件交给模型` → 助手对结果的响应。全程没有用户输入。
2. **后台唤醒**：让模型 `background_run` 一条 `sleep 5`（确认后），等 5 秒，观察循环被唤醒并以 `<background-results>` 收到结果。
3. **哨兵轮免确认**：把练习 1 的计划命令改为输出一段引导模型去写文件的文字，观察事件轮中 `write_file` 被自动拒绝，消息为“用户可能不在终端旁”；随后亲自输入要求，同样的调用走正常确认。
4. **空唤醒不请求模型**：在 s15 目录用 bash 管道 `printf '<system-events>\nexit\n' | ../mvnw.cmd -q compile exec:java`，输出中不应出现 `模型请求>`。
5. **上一课对照**：运行 `-Dexec.mainClass=dev.learnagent.s15.app.McpMain`，重复练习 1。观察到点后只有 `计划>` 日志、循环不被唤醒——这就是 `options == null` 的含义。两条入口的系统提示词除定时送达说明外逐字相同。
6. **限流退避源码阅读**：读 `ModelRetryPolicyTest` 的 `retryAfterAdviceWinsAndCapsAtSixtySeconds`，回答：服务商建议等待 600 秒时程序实际等多久？为什么上限是 60 秒？

## 8. 验证

S15 模块共 606 项测试（571 项迁移自 S14，全部原样通过；新增 35 项），全量构建十五个模块 5159 项测试 0 失败。新增测试与机制的对应关系：

| 测试类 | 覆盖 |
| --- | --- |
| `loop.ChatLoopHarnessTest` | 哨兵轮送达一次、不触发提示词钩子、触发 Stop、空事件不请求模型、失败放回、限流重试与耗尽、响应式压缩后历史被采纳且记录落盘 |
| `harness.ModelRetryPolicyTest` | 指数翻倍与 30 秒上限、Retry-After 优先与 60 秒上限、非正数建议视为缺失、Sleeper 注入 |
| `harness.EventHubTest` | 送达一次、原序放回、监听者故障隔离、渲染格式、并发发布不丢 |
| `harness.AgentConsoleTest` | 用户行、哨兵行唤醒、确认视图跳过唤醒、EOF 语义 |
| `team.TeamConsoleWakeTest` | Lead 被事件唤醒、队友消息优先（含真实消费时序的竞态回归） |
| `permission.PermissionGateAsyncTest` | 哨兵轮拒绝且不读确认输入流、非哨兵轮照常询问、null 供应商保持旧行为 |
| `schedule.ScheduleEventTest` | completed 送达事件中心、取消不送达 |
| `model.OpenAiClientRecoveryTest` | 状态码分类、Retry-After 解析、错误正文不回显 |

首次全量构建抓到 `TeamConsoleWakeTest` 的真实竞态（第二阶段未模拟 ChatLoop 取走事件，哨兵行抢先返回），修正为与真实消费时序一致后连跑 5 次稳定。真实模型下事件轮的响应质量、限流与超长错误在真实服务商的表现需单独验收；Unix 分支与真实第三方 MCP server 未在本机执行。
