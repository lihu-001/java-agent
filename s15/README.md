# S15：Integrated Harness 集成运行时

复制 S14 的全部能力。本课**不新增独立机制**，而是把 S01—S14 的机制整合到同一条请求管线，补上组合时才暴露的缺口：

- **事件唤醒**：定时计划的非取消终态进入对话（S12 起结果只写回计划文件，本课改变该边界）；后台任务完成与定时结束会唤醒空闲的循环，程序自动发起的轮次用哨兵行 `<system-events>` 进入，不记录用户消息、不触发 `UserPromptSubmit`，且**不能交互确认**（`PermissionGate` 直接拒绝 ASK，不阻塞在终端）。
- **限流退避**：HTTP 429/503/529 识别为可重试错误（`ModelUnavailableException`），按 `Retry-After` 或指数退避重试（默认最多 3 次，2s→4s→8s，上限 30s；`Retry-After` 上限 60s）。S01 起"失败不重试"的边界在本课按此重新定义。
- **响应式压缩**：模型返回上下文超长错误（`ContextTooLargeException`）时，不等阈值立即总结一次并重试；压缩不出摘要时按普通失败处理。

**上一课入口 `app.McpMain` 行为与 S14 完全相同**（options 为 null 时所有增强关闭）。

## 包结构

根包只保留默认入口 `HarnessMain`，S14 的 `McpMain` 移入 `app` 作为上一课入口：

```text
dev.learnagent.s15
├── HarnessMain               # 默认入口：AgentConsole + EventHub + 退避策略，交给 TeamMain 组装
├── app                       # ToolUseMain … ScheduleMain / TeamMain / McpMain（前几课入口）
├── permission / loop / model / tool / hook / todo / subagent / skill / compact / memory / task
│   / background / schedule / team / mcp                        # 与 S14 相同（部分有 S15 增量）
└── harness                   # 本课新增
    ├── TurnEvents            # 程序事件哨兵行 <system-events> 与识别
    ├── EventHub              # 定时事件队列、唤醒监听者、哨兵轮状态
    ├── AgentConsole          # 单 Agent 的事件队列控制台（prompts / answers 两个视图）
    ├── ModelRetryPolicy      # 限流退避：指数翻倍、Retry-After 优先、可注入 Sleeper
    └── HarnessOptions        # 装配可选增强（events + retry + loopInput），null 时与 S14 相同
```

学习顺序：[harness/TurnEvents](src/main/java/dev/learnagent/s15/harness/TurnEvents.java) → [harness/EventHub](src/main/java/dev/learnagent/s15/harness/EventHub.java) → [harness/AgentConsole](src/main/java/dev/learnagent/s15/harness/AgentConsole.java) → [model/ModelUnavailableException](src/main/java/dev/learnagent/s15/model/ModelUnavailableException.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s15/loop/ChatLoop.java) 的哨兵轮与 `completeWithRecovery` → [HarnessMain](src/main/java/dev/learnagent/s15/HarnessMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `harness.*` | 新增，见上 |
| `model.ModelUnavailableException` / `model.ContextTooLargeException` | 新增：限流与上下文超长的类型化异常 |
| `model.OpenAiClient` | 非 2xx 时分类：429/503/529 → 可重试（读取 `Retry-After` 数字秒）；400/413 且正文匹配常见超长文案 → 上下文超长；错误正文不进入异常消息 |
| `loop.ChatLoop` | 新增带 `EventHub` 与 `ModelRetryPolicy` 的构造方法（旧重载全部保留）；哨兵轮跳过提示词钩子、事件作为新 user 消息进入、空事件不请求模型；每次请求前合并送达定时事件与后台通知；限流重试与响应式压缩（`completeWithRecovery`）；失败未保存时事件放回原队列 |
| `compact.ContextCompactor` | 新增 `beforeRequest(history, output, force)` 重载：force 不看阈值（响应式压缩用），阈值与手动登记行为不变 |
| `permission.PermissionGate` | 新增带 `BooleanSupplier` 的构造方法：哨兵轮期间 ASK 直接拒绝，不读确认输入流 |
| `background.BackgroundManager` | 完成结果先入队再触发监听器（唤醒时一定能取到）；新增 `hasQueuedNotifications()` |
| `schedule.ScheduleManager` | 新增带事件出口的构造方法：非取消终态把一行摘要与截断输出发给事件中心；取消（会话结束）不发送 |
| `team.TeamConsole` | `prompts(mail)` 增加 hub 重载：程序事件唤醒空闲的 Lead，队友消息优先 |
| `app.ScheduleMain` / `app.TeamMain` | 新增带 `HarnessOptions` 的 `createSession` 重载：接线事件中心、后台完成唤醒、哨兵轮免确认与退避策略；options 为 null 时与 S14 完全相同 |

`ChatLoop` 与 `ToolRegistry` 的既有语义不变：普通轮次的通知仍附加到最后一条 user/tool 消息末尾，不新增消息角色，每条只送达一次。

## 运行时行为

- **事件轮**：循环空闲时定时结束或后台完成会唤醒控制台，以 `<system-events>` 作为输入进入一轮；事件合并渲染为 `<system-events>…</system-events>`（定时在前、后台 `<background-results>` 在后），作为一条新 user 消息请求模型。没有待送达事件时不请求模型。
- **哨兵轮确认**：轮次期间 `EventHub.setSyntheticTurn(true)`，ASK 类工具调用立即返回拒绝结果，提示"用户可能不在终端旁"。队友的确认请求仍走主线程询问，不受影响。
- **失败恢复**：重试耗尽、压缩不出摘要时回退到 S14 行为（报错结束本轮；未保存时事件放回队列原顺序再交付）。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s15 -q compile exec:java`。创建一条一分钟计划（如 `* * * * *` 执行 `echo`）后，到点会在终端看到 `计划> … 运行结束`，下一瞬间循环被唤醒并把结果交给模型，无需输入。

协议细节和设计取舍见 [课程文档](../docs/lessons/s15-integrated-harness.md)。
