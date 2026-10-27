# S18：Slash Commands 斜杠命令

本课在独立的 s18 子工程中实现，以 S17 完整源码为起点新增斜杠命令。入口为 `dev.learnagent.s18.CommandMain`。S17 的 `GoalMain` 移入 `app`，不接斜杠命令，行为与 S17 默认入口完全相同。本课新增 `s18.command` 包（10 个类），不新增工具，不改系统提示词。`ChatLoop` 增加一个可选构造参数；`PermissionPolicy`、`ToolRegistry`、`SubagentRunner` 一行未改。

## 1. 有些事不该问模型

到 S17 为止，Agent 已经有技能、记忆、任务图、后台命令、定时计划、队友、MCP server 和工作流，但用户想知道它们的状态时，只有两个办法：

- **翻启动日志**。只能看到启动那一刻的状态，之后写入的记忆、新建的任务都不在里面。
- **问模型**。"现在有哪些技能？"要花一次模型调用；模型只能根据系统提示词或调用工具来回答，看不到程序内部的状态（MCP 是否断开、上下文有多大、注册了哪些 Hook）。

还有一类操作本来就不该交给模型，比如"清空对话重新开始""现在压缩一下"。它们改的是对话本身，不是工作目录。

S17 的 `/goal` 已经开了头：它是**用户对程序**说的话，不是对模型说的。本课把这个特例推广成一个注册表，补上常用命令。

```text
终端一行输入
   │
   ├─ exit / quit ───────────────────────────────▶ 退出（S01 起）
   ├─ 哨兵行 <system-events> ─────────────────────▶ 事件轮（S15）
   ├─ /名称 [参数] ──▶ CommandRegistry.dispatch
   │                    ├─ PRINT  只打印 ───────────▶ 等下一行（不请求模型、不触发 Hook、不进历史）
   │                    ├─ EXIT ────────────────────▶ 退出
   │                    └─ SUBMIT 展开后的提示词 ──┐
   └─ 其他文字 ───────────────────────────────────┴▶ UserPromptSubmit → 记录 → 请求模型
```

## 2. 两类命令

| 类型 | 是否请求模型 | 是否触发 Hook | 是否进入历史 | 例子 |
| --- | --- | --- | --- | --- |
| 本地命令 | 否 | 否 | 否 | `/clear`、`/skills`、`/status`、`/goal`（查看） |
| 提示词命令 | 是 | 经过 `UserPromptSubmit` | 是 | `/goal <条件>`、`/<技能名>` |

这个划分来自 S17：`/goal` 查看和清除只打印，设置则把条件包装为本轮任务，经 Hook 放行后才生效。本课把它写成 `CommandResult` 的三种结果：

- `PRINT(text)`：主循环打印后回到提示符。
- `EXIT`：主循环打印"对话已结束。"并返回。
- `SUBMIT(prompt, onAccepted, rejectedNote)`：`prompt` 替换本轮输入，照常进入 `UserPromptSubmit`。放行后调用 `onAccepted`，例如 `goal.set`；被拒绝时在"钩子拒绝"后追加 `rejectedNote`，例如"目标未设置。"。

**本地命令为什么不触发 Hook？** `UserPromptSubmit` 的语义是"提示词加入历史、请求模型之前"。本地命令不是提示词，也不会到达模型。如果让它触发 Hook，审计 Hook 会把 `/status` 记成一次用户请求，拦截 Hook 也可能挡住 `/clear` 这类本该随时可用的操作。

## 3. 识别规则：什么算命令

`CommandLine.parse` 只认一种形式：`/` 后面紧跟名称，名称是小写字母开头的小写字母、数字与连字符；名称之后要么是结尾，要么是空白（全角空格也算），空白后的内容是参数。

| 输入 | 结果 |
| --- | --- |
| `/help`、`  /help  ` | 命令 `help`，无参数 |
| `/compact   保留文件名 ` | 命令 `compact`，参数 `保留文件名` |
| `/goal　测试通过`（全角空格） | 命令 `goal`，参数 `测试通过` |
| `/usr/bin/env 是什么`、`/skills/` | 名称后是 `/`，不是命令，作为提示词 |
| `/Help`、`/1abc`、`/`、`/中文` | 不是命令格式，作为提示词 |
| `请运行 /help` | 不以 `/` 开头，作为提示词 |

**未知命令不发给模型。** 形式正确但没有注册的命令，例如把 `/skills` 拼成 `/skill`，只打印"未知命令"，并按前缀给出相近的名字。如果把它当提示词发出去，模型会认真回答一个用户并不想问的问题，还白花一次调用。代价是：恰好想以 `/abc` 开头问模型的话，需要换个说法（例如前面加一个字）。

**参数检查在注册表里做。** 不接受参数的命令（`takesArgument=false`）带了参数时，注册表直接拒绝，不调用处理函数，例如 `/status 详细`。这样每个命令不用自己检查。

## 4. 注册表接入主循环

`ChatLoop` 新增一个构造参数 `CommandRegistry commands`，为 null 时与 S17 完全相同。非 null 时，处理顺序是：

1. 读一行；`exit`/`quit`/EOF 退出（不变）。
2. 哨兵行跳过命令识别（S15 的事件轮不是用户输入）。
3. 交给 `commands.dispatch(line, context)`。返回 null 说明不是命令，照常作为提示词。
4. 命令执行后，如果 `context.historyReplaced()`，主循环采纳新历史。
5. 按结果 PRINT / EXIT / SUBMIT 处理。

`CommandContext` 只暴露命令真正需要的主循环状态：当前历史（不可修改的快照，第一条必须是系统提示词）、工具定义、终端输出，以及 `replaceHistory`。存储、管理器这些会话部件不经过 context，而是在组装时通过 `CommandSources` 交给命令，与 S16 `WorkflowStarter`、S17 `GoalStarter` 的组装模式相同：

```java
// ScheduleMain / TeamMain 的组装末尾（S18）
CommandRegistry slash = commands == null ? null : commands.bind(new CommandSources(config, projectDirectory,
        catalog, mcp, hooks, memories, todos, tasks, background, schedules, zone,
        workflow == null ? null : workflow.store(), team, goal, compactor, transcripts), log);
```

`/goal` 怎么办？注册表存在时，S17 的 `/goal` 分支不再执行，`/goal` 作为注册表中的一个提示词命令处理，行为逐条对照 S17：查看与清除打印，设置经过 Hook 后才生效，条件超长打印说明。注册表为 null（上一课入口）时，S17 的分支照旧。两条路径共用同一组 `onAccepted` / `rejectedNote` 变量，所以 S17 路径的输出也逐字不变。

## 5. 会话控制命令

### `/clear`

清空对话，只保留系统提示词，同时清空任务清单（`todo_write` 的会话清单）和当前目标。清单和目标都是"这段对话在做什么"，对话清空后它们就失去了上下文。

**先保存，再清空。** 与 S08 压缩相同：被替换的内容必须能找回。`/clear` 先把完整历史写入 `.transcripts/transcript-时间.jsonl`，写入失败就打印原因、**不清空**。没有可清的消息时不写文件；对话、清单和目标都为空时只提示“当前对话为空”。

清空**不影响**：后台命令、队友、定时计划、记忆和任务文件。这些属于工作目录或进程，不属于这段对话。已完成但尚未送达的后台结果，会在下一轮附加到新对话的第一条消息里。团队模式下只清空 Lead 的对话，队友各自的历史不变。

### `/compact [重点]`

立即做一次 S08 的总结压缩。`ContextCompactor` 新增 `compactNow`：

- **不看阈值**：用户要求压缩就压缩。
- **不走登记流程**：S08 的 `compact` 工具只能登记，要等本批工具结果补齐后再执行，否则会打断 `tool_call` 协议。命令只在两轮之间执行，历史总是完整的，可以立即总结。
- 其余与自动压缩**共用同一段代码**：先保存完整记录，再用不带工具、不计调用上限、不经过 Hook 的独立请求总结；`重点` 附加为"本次压缩请特别保留：…"，上限 200 字符，与 `compact` 工具一致。没有可总结的内容时不请求模型；总结失败时历史不变。

### `/context`

```text
上下文> 对话 12 条消息（user 3、assistant 5、tool 4），约 18342 字符
上下文> 发送前微压缩后约 9120 字符，超过 50000 字符自动总结（当前 18%）
上下文> 系统提示词 6210 字符，工具 31 个（定义约 14873 字符），这两部分每次请求都会发送，不计入阈值
上下文> 字符数不是 token 计数，只用于和压缩阈值比较。
```

数字用的是 S08 判断阈值的同一套算法（`MicroCompact.size`，不含系统提示词，微压缩之后）。所以"当前 18%"正好说明离自动压缩还有多远。系统提示词与工具定义每次请求都会发送，但压缩缩不小它们，所以单独列出。

### `/export`

把当前历史（含系统提示词）写入 `.transcripts/`，格式与压缩时保存的完整记录相同。

## 6. 查看命令

| 命令 | 数据来源 | 说明 |
| --- | --- | --- |
| `/status` | `AgentConfig` 等 | 模型、判断器、接口、工作目录、各项上限、团队/MCP/工作流状态；api-key 只说"已配置" |
| `/tools` | `CommandContext.tools()` | 主 Agent 实际发给模型的工具定义 |
| `/skills` | `SkillCatalog` | 启动时扫描的目录；正文修改即时生效，增删需重启（S07） |
| `/mcp` | `McpManager.status()`（新增） | 每个 server 是否仍连接、工具、哪些在 `allow` 中免确认 |
| `/hooks` | `AgentHooks.descriptions()`（新增） | 主 Agent 的回调按执行顺序列出 |
| `/memory` | `MemoryStore.list` | 每次重读 `.memory/`；与启动时进入系统提示词的索引可能不同 |
| `/todos` | `TodoList.render` | 会话清单 |
| `/tasks` | `TaskStore.list` + `render` | 与 `task_list` 同一渲染 |
| `/bg` | `BackgroundManager.list` | 与 `background_check` 同一渲染，不含定时运行 |
| `/schedules` | `ScheduleStore.list` + `render` | 与 `schedule_list` 同一渲染 |
| `/workflows` | `WorkflowStore.list` + `render` | 与 `workflow_list` 同一渲染；只有启用工作流时注册 |
| `/team` | `TeamManager.render` | 只在团队模式注册 |

**文件仍是唯一数据源。** 存储类命令每次都重读文件，和对应工具看到的一致。无效文件的告警附在输出末尾。

**`/hooks` 需要回调能描述自己。** S04 的 `AgentHook` 只有四个事件方法，本课增加一个默认方法 `describe()`，默认显示类名（匿名类显示"Java 回调"）。`LoggingHook` 显示"内置日志"；`CommandHook` 显示事件、matcher、命令与超时，命令中的控制字符转义显示；团队的任务唤醒回调说明自己做什么。这样 `/hooks` 列出的就是**实际生效**的回调：用户 JSON Hook 没有通过信任确认时不会出现，而不是把 `hook.json` 的内容原样念一遍。

## 7. 提示词命令

### `/goal`

见第 4 节：并入注册表，行为与 S17 相同。

### `/<技能名> [补充说明]`

S07 起，技能由模型按需 `load_skill`，是否加载由模型决定。用户明确知道要用哪个技能时，`/code-review src/Foo.java` 比"请用 code-review 技能审查 src/Foo.java"更直接，也更确定：

```text
[技能命令] 用户输入 /code-review 直接调用了下面的技能，请按技能说明完成本次任务，不需要再用 load_skill 加载它。
<skill name="code-review">
……技能正文（每次重读，超过 12000 字符截断，与 load_skill 同格式）……
</skill>

用户补充说明：src/Foo.java
```

这段文字作为本轮用户输入，照常经过 `UserPromptSubmit` 并进入历史。**技能文本不授予权限**（S07 起的规则不变）：按技能说明要写文件、运行 Shell，仍然逐次确认。

**重名与非法名称。** 注册顺序是 `/help`、内置命令、技能命令，先注册的优先。名为 `help`、`clear` 等的技能不注册为命令，启动时告警，仍可由模型 `load_skill`。技能名允许数字开头（S07 的规则），命令名不允许，这类技能同样只告警跳过。

## 8. 和前几课机制的衔接

- **S04 Hook**：本地命令不触发任何 Hook；提示词命令只触发 `UserPromptSubmit`，之后的工具调用照常触发 Pre/PostToolUse。
- **S08 压缩**：`/clear` 与 `/compact` 都先写 `.transcripts/`；`/compact` 的总结请求与自动压缩完全相同。
- **S13 团队**：主线程是唯一读输入的线程。Lead 正在执行一轮时输入的命令进入 `TeamConsole` 队列，这一轮结束后才执行，所以不能在 Lead 忙时用 `/team` 立刻查看队友。命令输出整段加锁，不会和队友的输出交错。
- **S15 事件轮**：哨兵行不经过注册表。`/clear` 之后到达的事件照常送达新对话。
- **S17 目标**：`/clear` 会清除目标，因为续跑消息依赖的对话已经不在了。`/compact` 不影响目标，条件保存在控制器里。

## 9. 容易出错的地方

- **命令改了历史，主循环要采纳。** `history` 是 `converse` 的局部变量，命令拿到的是快照。`/clear` 之后如果主循环忘了用 `context.history()` 替换，下一轮还会带着旧对话。
- **先保存，再清空。** 顺序反了，写入失败时对话已经丢了。
- **未知命令不能悄悄发给模型。** 这是本课最容易被忽略的一条，测试里专门覆盖。
- **不要让本地命令触发 `UserPromptSubmit`。** 否则拦截型 Hook 会让用户连 `/help` 都用不了。
- **上一课入口逐字不变。** 注册表为 null 时，`/help` 是普通提示词，横幅也没有"输入 /help"这句，测试对照了这一点。

## 10. 没有实现的部分

- **`/model` 切换模型。** 主 Agent、子代理、队友、压缩器共用一个 `OpenAiClient`，模型在构造时固定。运行时切换要改模型层，并决定子代理和队友是否跟着切换。`/status` 只显示当前模型。
- **`/cost` 用量统计。** `OpenAiClient` 不解析响应中的 `usage`。
- **`/resume` 恢复会话、`/rewind` 回退。** 需要会话持久化和回放机制。`.transcripts/` 只是记录，不是可恢复的会话状态。
- **自定义命令目录。** 例如 `.agents/commands/<name>.md` 存放提示词模板、`$ARGUMENTS` 替换参数。它和技能目录对称，但会再带出一套文件格式与校验，留作练习。
- **取消单个后台命令（`/bg kill`）。** `BackgroundManager` 没有取消单个任务的接口。
- **补全与历史。** 终端是普通的行输入，没有 Tab 补全和上下键历史。

## 11. 练习

1. **看状态**：启动后依次输入 `/help`、`/status`、`/tools`、`/skills`、`/hooks`，确认终端没有出现"模型请求>"。
2. **清空**：聊两轮后 `/context` 看用量，再 `/clear`，到工作目录的 `.transcripts/` 找到刚写入的记录，然后问模型"我刚才说了什么"。
3. **压缩**：聊几轮后 `/compact 保留所有文件路径`，再 `/context` 对比前后的字符数。
4. **技能命令**：`/code-review playground/example.txt`；再修改 `s18/.agents/skills/code-review/SKILL.md` 的正文，不重启，再调用一次，确认用的是新正文。
5. **拼错**：输入 `/skill`、`/statsu`，观察提示和建议；再输入 `/usr/bin/env 是什么`，确认它作为提示词发给了模型。
6. **Hook 隔离**：在 `s18/.agents/hook.json` 写一个拒绝所有提示词的 UserPromptSubmit 命令，确认 `/status`、`/clear` 仍可用，而 `/code-review` 被拒绝。
7. **扩展**：仿照 `SessionCommands`，写一个 `/undo`，删除最近一轮（从最后一条 user 输入到末尾），想清楚已执行的工具副作用为什么不会被撤销。
8. **上一课对照**：运行 `-Dexec.mainClass=dev.learnagent.s18.app.GoalMain`，`/help` 应作为普通提示词发给模型。

## 12. 验证

S18 模块共 744 项测试：702 项迁移自 S17，原样保留；新增 42 项：command 包 38 项，迁移类中补充 4 项（Hook 描述 1 项、`compactNow` 2 项、MCP 状态 1 项）。新增测试与机制的对应关系：

| 测试类 | 覆盖 |
| --- | --- |
| `command.CommandLineTest` | 名称与参数去空白、全角空格分隔、十三种非命令文本（路径、大写、数字开头、末尾斜杠、中文等）、名称校验 |
| `command.CommandRegistryTest` | `/help` 在最前并列出用法/别名/提示词标记、别名分派、非命令返回 null、未知命令按前缀建议、多余参数拒绝且不执行、参数去空白、重名与别名冲突告警且先注册的优先、处理函数 I/O 异常上抛、非法名称、context 要求系统提示词开头与历史替换 |
| `command.CommandLoopTest` | 通过 TeamMain 的完整会话：十六个本地命令都不请求模型、不触发 Hook，空对话 `/clear` 不写记录，单 Agent 模式下 `/team` 未注册，api-key 不出现在输出中；不新增工具且系统提示词与 S17 逐字相同；上一课入口 `/help` 是普通提示词；未知命令不发送、路径形式照常发送；`/exit`、`/quit`；`/clear` 保存记录后清空历史、清单与目标，且下一轮只有系统提示词与新输入；记录写入失败时不清空；`/compact` 空对话不请求、重点超长拒绝、带重点总结且不经过 Hook；`/context` 与 `/export`；`/goal` 经注册表时 Hook 拒绝不设置、超长提示、设置后达成；技能命令经过 UserPromptSubmit 并记录、与内置命令重名和数字开头的技能告警；命令失败打印后会话继续；团队模式 `/team` 与任务唤醒回调 |
| `hook.HookConfigTest`（补充） | 用户命令 Hook 的描述含事件、matcher、命令与超时，控制字符转义；内置日志与匿名回调的描述 |
| `compact.ContextCompactorTest`（补充） | `compactNow` 不看阈值、附加重点、不走登记流程；没有可总结内容或总结失败时历史不变 |
| `mcp.McpLoopTest`（补充） | `status()` 报告连接、工具与免确认集合，会话结束后显示断开 |

迁移自前几课的文件存储测试在 Windows 上偶发临时文件替换 `AccessDeniedException`，与 S13—S17 的记录相同，详见[实战规范](../JAVA_PRACTICE_SPEC.md#s18-slash-commands-验证)。Unix 分支未在本机执行。
