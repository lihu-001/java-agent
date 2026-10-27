# S15 Integrated Harness：多机制，一条循环

## 1. 本课定位

S01—S14 每课证明"本课机制单独能跑"：循环、工具、权限、Hook、清单、子代理、技能、压缩、记忆、任务图、后台任务、定时计划、团队、MCP。但每课的验证都是单机制视角，机制叠加时的顺序与相互作用从未被统一定义。S15 不新增独立机制，而是把所有机制挂到同一条请求管线上，并补上组合时才暴露的缺口。

每一轮请求的完整管线：

```text
输入来源：用户输入 / 程序事件（定时终态、后台完成）
→ UserPromptSubmit（仅用户输入）
→ 取用并合并程序事件（定时 <system-events> 在前，后台 <background-results> 在后）
→ 上下文压缩（阈值自动 / compact 登记）
→ 请求模型（限流退避；上下文超长先响应式压缩）
→ 无工具调用：Stop，本轮结束
→ 有工具调用：PreToolUse → 权限（哨兵轮 ASK 自动拒绝）→ 执行 → PostToolUse → 回传结果 → 下一轮
```

## 2. 事件唤醒：程序也能发起一轮

S14 之前循环只由用户输入和队友消息驱动；后台通知要等下一次输入才送达，定时结果从不进对话。S15 引入三条新规则：

1. **定时终态进对话**：`ScheduleManager` 新增事件出口，非取消终态（completed/failed/timed_out）把一行摘要与截断输出发给 `EventHub`。S12"结果不进对话"的边界到此为止。
2. **空闲唤醒**：`EventHub` 的监听者唤醒控制台（单 Agent 用 `AgentConsole`，团队模式复用 `TeamConsole.wake`）；控制台以哨兵行 `<system-events>` 作为 `readLine` 的返回值，循环随即进入"哨兵轮"。后台完成仍走 S11 自己的通知队列，唤醒只负责叫人。
3. **哨兵轮语义**：不是用户输入——不记录 user 消息、不触发 `UserPromptSubmit`；事件合并渲染成一条新 user 消息；没有待送达事件时直接回到等待，不空请求模型；轮次期间 `EventHub.setSyntheticTurn(true)`，`PermissionGate` 对 ASK 立即返回拒绝，**程序自动发起的轮次不能阻塞在终端确认上**（用户可能不在）。队友的确认仍排队交主线程，不受影响。

失败语义与 S11 一致：本轮未保存时事件按原顺序放回队列，下次再交付，每条只送达一次。`BackgroundManager` 改为先入队再触发完成监听器，保证唤醒循环醒来时一定取得到结果。

## 3. 错误恢复：限流退避与响应式压缩

S01 的"失败不重试"是教学边界，真实的编程 Agent 需要两类可恢复错误：

- **限流（`ModelUnavailableException`）**：HTTP 429/503/529。`OpenAiClient` 读取 `Retry-After` 数字秒（HTTP-date 不支持），`ModelRetryPolicy` 优先采用（上限 60 秒），否则指数退避 2s→4s→8s（上限 30 秒）。默认最多重试 3 次；`Sleeper` 可注入，测试不真正睡眠。重试耗尽后按 S14 的普通失败处理。
- **上下文超长（`ContextTooLargeException`）**：HTTP 400/413 且错误正文匹配常见文案（`context_length_exceeded`、`maximum context length`、`prompt is too long` 等）。触发时**不等阈值**立即总结一次并重试（`ContextCompactor.beforeRequest(history, output, force)`）；压缩不出摘要时按普通失败处理。错误正文可能含敏感信息，任何异常消息都不回显正文。

其余 HTTP 错误保持通用 `IOException`，行为与 S14 相同。

## 4. 装配：HarnessOptions 与三个入口

`harness.HarnessOptions(events, retry, loopInput)` 是集成的开关。`options == null` 时所有增强关闭：

| 入口 | options | 行为 |
| --- | --- | --- |
| `HarnessMain`（默认） | 有 | 全部增强 |
| `app.McpMain`（S14 入口） | 无 | 与 S14 完全相同 |
| `app.ScheduleMain` / `app.TeamMain` 新重载 | 按调用者 | 供嵌入与测试 |

旧构造器与旧重载全部保留；`ChatLoop` 既有重载逐层委托到最全的十个参数版本。关闭顺序不变：拒绝待定确认 → 停队友 → 取消后台（同时停调度器）→ 关 MCP server。

## 5. 运行与验收

```powershell
.\mvnw.cmd -pl s15 test
.\mvnw.cmd -pl s15 -q compile exec:java
```

手动验证事件唤醒：进入 S15 后让模型 `schedule_create` 一条 `* * * * *` 的 `echo` 计划，然后不再输入。到点时终端先打印 `计划> … 运行结束`，随即循环被唤醒，输出 `事件> 已将程序事件交给模型` 与助手对该结果的响应——全程无用户输入。

自动测试的新增覆盖（每条对应一个组合缺口）：

- 哨兵轮：事件作为新 user 消息送达一次、不触发提示词钩子、正常触发 Stop、空事件不请求模型、失败放回原队列（`ChatLoopHarnessTest`）。
- 限流：`Retry-After` 优先、指数翻倍与上限、重试耗尽按普通失败处理（`ModelRetryPolicyTest`、`ChatLoopHarnessTest`）。
- 响应式压缩：force 不看阈值、无可总结内容返回未压缩、两参重载行为不变（`ReactiveCompactTest`、`ChatLoopHarnessTest`）。
- 状态码分类：429/503/529 与 400/413 超长文案、其余保持通用失败、正文不回显（`OpenAiClientRecoveryTest`）。
- 哨兵轮免确认：ASK 直接拒绝且不读确认输入流，非哨兵轮照常询问（`PermissionGateAsyncTest`）。
- 控制台唤醒：单 Agent 与团队模式的哨兵行、队友消息优先、EOF 语义（`AgentConsoleTest`、`TeamConsoleWakeTest`）。
- 定时事件：completed 送达、cancelled 不送达（`ScheduleEventTest`）。

## 6. 与上游的差异

上游 s15 用 Python 把权限实现为 PreToolUse Hook、每轮动态组装工具池并引入 `connect_mcp` 运行时连接。本课保持既有架构：权限在 `ToolRegistry` 分派前（S03），MCP 仍只在启动时经信任确认连接（S14）；`ChatLoop` 与 `ToolRegistry` 的既有语义不破坏，集成以可选参数注入。上游的 max_tokens 截断升级未实现，属 S16 之后可扩展项。
