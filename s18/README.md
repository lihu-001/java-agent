# S18：Slash Commands 斜杠命令

复制 S17 的全部能力。S01—S17 里，终端输入只有两种去向：`exit`/`quit` 退出，其他都作为提示词交给模型（S17 的 `/goal` 是唯一的例外，写死在主循环里）。想看一眼加载了哪些技能、MCP 连上没有、上下文还剩多少，只能翻启动日志或者去问模型。问模型既费调用次数，模型也看不到程序内部的状态。本课把终端命令做成一个**注册表**，补上常用的会话命令。

- **两类命令**（沿用 S17 `/goal` 的规则）：
  - **本地命令**只在终端执行：不请求模型、不触发 Hook、不进入对话历史。
  - **提示词命令**（`/help` 中标 `*`）把内容展开成本轮任务交给模型，照常经过 `UserPromptSubmit`，被拒绝时不生效。
- **会话控制**：
  - `/clear` 清空对话，只保留系统提示词，同时清空任务清单和当前目标。清空前先把完整记录写入 `.transcripts/`，写不出来就不清空。
  - `/compact [重点]` 立即总结压缩。
  - `/context` 查看上下文用量。
  - `/export` 把当前对话写入 `.transcripts/`。
  - `/exit`（`/quit`）退出。
- **查看状态**：`/status`、`/tools`、`/skills`、`/mcp`、`/hooks`、`/memory`、`/todos`、`/tasks`、`/bg`、`/schedules`、`/workflows`、`/team`（只在团队模式）。存储类命令每次执行都重读文件。
- **提示词命令**：
  - `/goal` 并入注册表，行为与 S17 相同。
  - 每个技能对应一个 `/<技能名> [补充说明]`：把技能正文和补充说明作为本轮任务提交，不用等模型自己决定是否 `load_skill`。
- **未知命令**：形如 `/名称`、但没有注册的输入只打印提示（附相近的命令名），不发给模型，避免拼错的命令被当作提示词发出去。不是命令格式的输入照常作为提示词发送，例如 `/usr/bin/env`、`/Goal`、`/`。

**上一课入口 `app.GoalMain` 行为与 S17 完全相同**（不接 `CommandStarter`，`/help` 只是普通提示词）。

## 包结构

根包只保留默认入口 `CommandMain`，S17 的 `GoalMain` 移入 `app` 作为上一课入口：

```text
dev.learnagent.s18
├── CommandMain                # 默认入口：与 app.GoalMain 相同的装配，外加 CommandStarter.builtin()
├── app                        # ToolUseMain … TeamMain / HarnessMain / WorkflowMain / GoalMain（前几课入口）
├── permission / model / tool / hook / todo / subagent / skill / compact / memory / task
│   / background / schedule / team / mcp / harness / workflow / goal    # 与 S17 相同（少量查看接口见下表）
├── loop.ChatLoop              # 新增带 CommandRegistry 的构造方法：输入先交给注册表
└── command                    # 本课新增
    ├── CommandLine            # 解析 “/名称 [参数]”：名称为小写字母开头的小写字母、数字与连字符
    ├── SlashCommand           # 命令：名称、别名、用法、说明、是否接受参数、是否提示词命令、处理函数
    ├── CommandResult          # PRINT（只打印）/ SUBMIT（作为本轮输入提交）/ EXIT（退出）
    ├── CommandContext         # 命令可见的主循环状态：对话历史（可替换）、工具定义、终端输出
    ├── CommandRegistry        # 查找、分派、/help、未知命令提示、重名告警
    ├── CommandSources         # 组装点交给命令的会话部件（存储、管理器、清单、目标、压缩器…）
    ├── SessionCommands        # /exit /clear /compact /context /export /goal
    ├── ViewCommands           # /status /tools /skills /mcp /hooks /memory /todos /tasks /bg /schedules /workflows /team
    ├── SkillCommands          # 每个技能一个 /<技能名> 提示词命令
    └── CommandStarter         # 组装回调：bind(sources, warnings)；builtin() 注册本课全部命令；null 时与 S17 相同
```

学习顺序：[command/CommandLine](src/main/java/dev/learnagent/s18/command/CommandLine.java) → [command/CommandRegistry](src/main/java/dev/learnagent/s18/command/CommandRegistry.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s18/loop/ChatLoop.java)（搜索 `S18`）→ [command/SessionCommands](src/main/java/dev/learnagent/s18/command/SessionCommands.java) → [command/ViewCommands](src/main/java/dev/learnagent/s18/command/ViewCommands.java) → [command/SkillCommands](src/main/java/dev/learnagent/s18/command/SkillCommands.java) → [CommandMain](src/main/java/dev/learnagent/s18/CommandMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `command.*` | 新增，见上 |
| `loop.ChatLoop` | 新增带 `CommandRegistry` 的构造方法，旧构造器逐层委托并传 null。注册表非 null 时，非哨兵行先交给注册表：PRINT 打印后等下一行；EXIT 结束；SUBMIT 替换本轮输入，`UserPromptSubmit` 放行后才调用 `onAccepted`（如设置目标）。命令替换了历史（`/clear`、`/compact`）时，主循环采纳新历史。注册表为 null 时仍走 S17 的 `/goal` 分支，行为不变 |
| `compact.ContextCompactor` | 新增 `compactNow(history, focus, output)`：不看阈值、不走 `compact` 工具的登记流程，立即总结；与自动压缩共用保存记录与总结的代码 |
| `hook.AgentHook` / `AgentHooks` | `AgentHook` 新增默认方法 `describe()`；`LoggingHook`、`CommandHook`、团队的任务唤醒回调各自覆盖；`AgentHooks.descriptions()` 按执行顺序列出，供 `/hooks` 使用 |
| `mcp.McpManager` | 新增 `status()`：每个 server 的连接状态、工具与免确认工具 |
| `workflow.WorkflowBinding` | 增加 `store` 字段，`/workflows` 与工作流工具读同一目录 |
| `background.BackgroundTools.render` / `skill.LoadSkillTool.render` | 改为公开，`/bg` 与 `/<技能名>` 复用同一渲染 |
| `app.ScheduleMain` / `app.TeamMain` | 新增带 `CommandStarter` 的 `createSession` 重载：主 Agent（团队模式为 Lead）的部件就绪后组成 `CommandSources` 交给 `bind`，得到注册表。工具与系统提示词与 S17 逐字相同 |
| `CommandMain` | 默认入口：与 `app.GoalMain` 相同的装配，外加 `CommandStarter.builtin()` |

本课不新增工具，不改系统提示词，`PermissionPolicy`、`ToolRegistry`、`SubagentRunner` 一行未改。命令是给**用户**用的终端功能，模型看不到也调用不了。

## 命令一览

| 命令 | 类型 | 作用 |
| --- | --- | --- |
| `/help` | 本地 | 列出全部命令；标 `*` 的是提示词命令 |
| `/exit`（`/quit`） | 本地 | 退出，与不带斜杠的 `exit` / `quit` 相同 |
| `/clear` | 本地 | 先把完整记录写入 `.transcripts/`，再清空对话（只保留系统提示词）、任务清单和当前目标。记录写入失败时不清空。后台命令、队友、定时计划、记忆和任务文件不受影响，后台结果仍会在下一轮送达 |
| `/compact [重点]` | 本地 | 立即总结压缩，与 S08 相同：先保存完整记录，再用独立的总结请求（不计调用上限、不经过 Hook）。重点说明最多 200 字符，没有可总结的对话时不请求模型 |
| `/context` | 本地 | 消息数（按角色）、粗略字符数、微压缩后的字符数与自动压缩阈值、系统提示词与工具定义的大小。字符数不是 token |
| `/export` | 本地 | 把当前对话写入 `.transcripts/`（JSONL，含系统提示词） |
| `/status` | 本地 | 模型、判断器、接口、工作目录、各项上限、团队/MCP/工作流状态；api-key 只说明已配置 |
| `/tools` | 本地 | 主 Agent 当前的工具与描述首行 |
| `/skills` | 本地 | 技能目录（名称与用途） |
| `/mcp` | 本地 | 每个 MCP server 是否连接、工具、哪些免确认；没有 server 时说明配置位置 |
| `/hooks` | 本地 | 主 Agent 生效的回调（按执行顺序）与用户配置位置 |
| `/memory` | 本地 | `.memory/` 中的记忆索引（以文件为准，与启动时进入提示词的快照可能不同） |
| `/todos` | 本地 | 会话任务清单 |
| `/tasks` | 本地 | `.tasks/` 任务图 |
| `/bg` | 本地 | 本次会话的后台命令（不含定时计划的运行） |
| `/schedules` | 本地 | `.schedules/` 定时计划 |
| `/workflows` | 本地 | `.workflows/` 工作流与进度 |
| `/team` | 本地 | 队友状态与持有的任务；只在团队模式注册 |
| `/goal [条件 \| clear]` | 提示词 | 与 S17 相同：带条件时设置目标并开始，不带参数查看，`clear` 等别名清除 |
| `/<技能名> [补充说明]` | 提示词 | 把技能正文（与 `load_skill` 同格式）和补充说明作为本轮任务提交 |

## 运行时行为

- **识别时机**：只在提示符处识别命令。S15 的哨兵行和权限确认的回答不当作命令。团队模式下 Lead 正在执行一轮时输入的命令会排队，等这一轮结束、回到提示符后才执行。这是"主线程是唯一读取用户输入的线程"带来的限制。
- **参数**：不接受参数的命令带了参数时直接拒绝，不执行。名称后要有空白（全角空格也算）才算参数，所以 `/skills/` 不是命令。
- **重名**：技能名与内置命令重名时（例如名为 `help` 的技能），内置命令优先，启动时告警。这个技能仍可由模型 `load_skill`。名称以数字开头的技能不能作为命令调用。
- **失败**：命令处理中的 I/O 错误打印为 `命令> 执行失败（…）`，会话继续。
- **只属于主 Agent**：只有主 Agent（团队模式为 Lead）有命令。`/clear` 只清空 Lead 的对话，队友各自的历史不受影响。子代理和队友没有命令。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s18 -q compile exec:java`。启动后先试：

```text
/help
/status
/skills
/context
/code-review playground/example.txt
```

协议细节和设计取舍见 [课程文档](../docs/lessons/s18-slash-commands.md)。
