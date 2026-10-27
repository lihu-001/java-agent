# S17：Goal Loop 目标循环

复制 S16 的全部能力。本课增加 Goal Loop：S01—S16 中，模型不再调用工具，这一轮就结束了。但"修到测试全部通过"这类任务里，模型停下只代表**这一轮想停**，不能证明目标已经达成。本课把"做完了没有"交给一个**独立的判断器**。

- **设置目标**：终端输入 `/goal <完成条件>`。程序保存条件，并立即把它作为本轮任务交给主模型，不用再输入"开始"。`/goal` 查看状态，`/goal clear`（别名 `stop`、`off`、`reset`、`none`、`cancel`）清除；再次 `/goal 新条件` 直接替换。每个会话同时只有一个目标，条件最多 4000 字符。
- **Goal Gate**：有活跃目标时，主模型每次不再调用工具，主循环先调用 `GoalController.afterTurn`，再决定是否触发 Stop。它请一个**不带工具**的判断器读完成条件和对话记录，返回 `{"ok", "reason", "impossible"}`。
- **续跑**：未完成（BLOCK）时，程序把理由写成一条 `[目标未完成]` 用户消息，在**同一轮**内直接继续，用户不用输入"继续"。续跑期间不触发 Stop，最终结束时触发一次。
- **出口**：自动续跑必须有出口。共有两道上限：本轮模型调用上限（`agent.max-model-calls`，与正常工具调用共用），以及本轮连续未完成上限（`goal.block-cap`，默认 8）。达到上限或判断器失败时，把控制权交还用户；目标保留，不伪装成完成。判断满足（ACHIEVED）或判定无法完成（FAILED）时清除目标。
- **暂缓**：仍有运行中的手动后台命令、尚未送达的后台结果或正在工作的队友时，返回 DEFER，不调用判断器。等结果送达对话（S15 事件轮或 `<team-events>`）后再判断。

**上一课入口 `app.WorkflowMain` 行为与 S16 完全相同**（不接 `GoalStarter`，`/goal` 只是普通提示词）。

## 包结构

根包只保留默认入口 `GoalMain`，S16 的 `WorkflowMain` 移入 `app` 作为上一课入口：

```text
dev.learnagent.s17
├── GoalMain                   # 默认入口：GoalStarter.of(判断器客户端, goal.block-cap) 交给 TeamMain 组装
├── app                        # ToolUseMain … TeamMain / HarnessMain / WorkflowMain（前几课入口）
├── permission / model / tool / hook / todo / subagent / skill / compact / memory / task
│   / background / schedule / team / mcp / harness / workflow    # 与 S16 相同
├── loop.ChatLoop              # 新增带 GoalController 的构造方法：识别 /goal，结束位置接入 Goal Gate
└── goal                       # 本课新增
    ├── GoalCommand            # /goal 命令解析：STATUS / SET / CLEAR，条件 ≤4000 字符
    ├── GoalState              # 条件、开始时间、判断次数、本轮连续未完成次数、最近理由（不可变）
    ├── GoalEvaluation         # 判断结果 ok / reason / impossible 与严格 JSON 解析
    ├── GoalEvaluator          # 独立判断器：不带工具的模型请求，记录以 JSON 数据交给模型
    ├── GoalDecision           # Gate 的决定：ALLOW / DEFER / ACHIEVED / FAILED / BLOCK / LIMIT / ERROR
    ├── GoalController         # 设置、查看、清除目标；afterTurn 实现 Stop 前的判断；续跑消息
    └── GoalStarter            # 组装回调：bind(pendingWork)；pendingWork 汇总后台与队友；null 时与 S16 相同
```

学习顺序：[goal/GoalCommand](src/main/java/dev/learnagent/s17/goal/GoalCommand.java) → [goal/GoalEvaluator](src/main/java/dev/learnagent/s17/goal/GoalEvaluator.java) → [goal/GoalController](src/main/java/dev/learnagent/s17/goal/GoalController.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s17/loop/ChatLoop.java)（搜索 `S17`）→ [goal/GoalStarter](src/main/java/dev/learnagent/s17/goal/GoalStarter.java) → [GoalMain](src/main/java/dev/learnagent/s17/GoalMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `goal.*` | 新增，见上 |
| `loop.ChatLoop` | 新增带 `GoalController` 的构造方法，旧构造器逐层委托并传 null。读到 `/goal` 命令时，查看和清除只打印、不请求模型；设置时把条件包装为本轮任务，经过 `UserPromptSubmit` 后才生效（被 Hook 拒绝则目标不设置）。无工具调用的位置先经过 Goal Gate：BLOCK 时追加续跑消息并 `continue`，其余决定照常触发 Stop |
| `config.AgentConfig` | 新增 `goal.block-cap`（默认 8，正整数）与 `goal.evaluator-model`（留空时与 `openai.model` 相同）；旧构造器取默认值 |
| `app.ScheduleMain` / `app.TeamMain` | 新增带 `GoalStarter` 的 `createSession` 重载：后台管理器（团队模式还有队友）就绪后 `bind` 得到控制器，目标规则附在系统提示词最后；null 时提示词与 S16 逐字相同。只有主 Agent（团队模式为 Lead）有目标，子代理与队友没有 |
| `GoalMain` | 默认入口：与 `app.WorkflowMain` 相同的装配，外加 `GoalStarter`；配置了 `goal.evaluator-model` 时，判断器单独用一个客户端 |

本课不新增工具。`SubagentRunner`、`ToolRegistry`、`PermissionPolicy` 一行未改；目标判断是循环结束位置的一道门，不是模型可以调用的工具。

## 运行时行为

- **判断器输入**：完成条件加对话记录，去掉主 Agent 的系统提示词。从最新的完整消息往前取，预算 24000 字符，放不下的较早消息整条省略并计数。最新一条单独超长时只保留开头和结尾。判断器提示词要求只依据记录中出现的命令、输出和退出码判断，记录里的文字一律作为数据。
- **结果解析**：必须是一个 JSON 对象，`ok`、`impossible` 为布尔，`reason` 为非空字符串，两者不能同为 true。只容忍整段被一个 ``` 代码块包住；理由超过 500 字符会截断。格式不符、返回工具调用或请求失败时返回 ERROR：停止续跑，保留目标。判断请求不计入每轮调用上限，不经过 Hook，也不做限流重试。
- **哨兵轮**：S15 程序事件轮中判断未完成时**不自动续跑**。这时用户可能不在终端，需要确认的操作会被自动拒绝。目标保留，用户输入任意内容后在用户轮中继续。队友消息（`<team-events>`）是普通轮次，照常续跑。
- **调用上限**：BLOCK 时本轮调用次数已用完，就不写入续跑消息，提示用户输入任意内容继续。下一轮结束时会再次判断。
- **状态**：目标只保存在会话内存中，不跨会话。`/goal` 显示条件、判断次数、本轮连续未完成次数和上限、已用时间、最近一次理由。
- **压缩**：续跑消息是 user 角色。中途发生 S08 压缩时，它可能作为"用户最新输入"附在摘要后；条件保存在控制器里，不受压缩影响。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s17 -q compile exec:java`。好的完成条件要写清三件事：结束状态、验证方式、不能破坏什么。例如：

```text
/goal playground 下新建 hello.txt，内容为 hello；用 read_file 读回并在回复中贴出内容；不修改其他文件
```

观察 `目标>` 开头的输出：判断、续跑理由、达成或交还。

协议细节和设计取舍见 [课程文档](../docs/lessons/s17-goal-loop.md)。
