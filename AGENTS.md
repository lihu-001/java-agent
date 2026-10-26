# Repository Guidelines

## 项目结构与模块组织

十四个独立 Maven 子工程，根 POM 仅聚合构建并统一版本：

- `s01`：最小文本对话，无工具；入口 `dev.learnagent.s01.AgentLoopMain`。
- `s02`：基于 S01 增加单工具、多工具分派、Shell 和文件工具；入口 `dev.learnagent.s02.ToolUseMain`。
- `s03`：基于 S02 增加权限策略和逐次确认；入口 `dev.learnagent.s03.PermissionMain`。
- `s04`：基于 S03 增加四类 Hook；入口 `dev.learnagent.s04.HooksMain`。
- `s05`：基于 S04 增加 TodoWrite 任务清单；入口 `dev.learnagent.s05.TodoMain`。
- `s06`：基于 S05 增加 Subagent 子代理；入口 `dev.learnagent.s06.SubagentMain`。
- `s07`：基于 S06 增加 Skill Loading 技能加载；入口 `dev.learnagent.s07.SkillMain`。
- `s08`：基于 S07 增加 Context Compact 上下文压缩；入口 `dev.learnagent.s08.CompactMain`。
- `s09`：基于 S08 增加 Memory 跨会话记忆；入口 `dev.learnagent.s09.MemoryMain`。
- `s10`：基于 S09 增加 Task System 持久化任务；入口 `dev.learnagent.s10.TaskMain`。
- `s11`：基于 S10 增加 Background Tasks 后台任务；入口 `dev.learnagent.s11.BackgroundMain`。
- `s12`：基于 S11 增加 Cron Scheduler 定时计划；入口 `dev.learnagent.s12.ScheduleMain`。
- `s13`：基于 S12 增加 Agent Teams 团队协作；入口 `dev.learnagent.s13.TeamMain`。
- `s14`：基于 S13 增加 MCP Plugin 外部工具接入；入口 `dev.learnagent.s14.McpMain`。

每课有完整源码、测试、POM、配置示例与 README；类全部在本模块的 `dev.learnagent.s0N` 包及其子包。后续课复制前一课阶段源码后修改扩展，不通过跨模块 Java 依赖或 common 工程隐藏实现。用户已明确用教学快照替代旧版“共用一份循环、不复制”的要求。

生产代码和测试分别放在各模块内部的 `src/main/java` 与 `src/test/java`，包路径对应。扩展课程前读 `docs/JAVA_PRACTICE_SPEC.md`，更新根 README 和模块 README 的职责、入口与增量。不要提前将工具、权限或 Hook 加入 S01。

S04 根包只保留默认入口 `HooksMain`；按职责拆为 `app`、`config`、`loop`、`model`、`tool`、`tool.file`、`permission`、`hook`。测试使用对应子包，不将新类重新堆回根包。Hook 接口与实现位于 `s04.hook`：`AgentHook`、`AgentHooks`、`LoggingHook`。事件为提示词记录前、工具调用前后、任务完成后。前置放行仍需权限检查；后置异常不覆盖结果；超限、失败、中断和退出不触发 Stop。

S04 命令 Hook 固定加载本子项目的 `s04/.agents/hook.json`，不受进程工作目录影响，不读取或回退到父工程 Hook 配置；`config.S04ProjectDirectory` 从本模块编译产物定位子项目。`HookConfig`、`CommandHook`、`HookCommandRunner` 均放在 `s04.hook`。配置错误阻止启动；命令在 S04 子项目目录运行，通过 UTF-8 stdin 接收 JSON，不将提示词或工具参数拼接到命令中。前置非零退出或执行失败拒绝，后置失败只告警；命令有独立超时和输出上限。Java Hook 属于 Agent 内置行为，JSON Hook 属于用户扩展；所有 HooksMain.createLoop 重载均保留两者，顺序为内置日志、追加的 Java Hook、用户 JSON Hook。Java 列表只追加，不替换默认注册。

用户 JSON Hook 必须经过独立的 HookTrust 启用确认，不能复用或绕过 S03 工具审批。首次或内容变化时支持 y/yes 记忆授权、once 单次会话、其余及 EOF 不启用；拒绝仍保留 Java Hook。HookTrustStore 默认写入用户目录 `.java-agent/hook-trust/`，项目 JSON 不得自授信任。HookTrustSnapshot 校验配置、字面量脚本和 trust_files 的内容，执行前检测变化；失效后前置拦截、后置告警，重启再确认。测试必须使用临时信任存储，不写真实用户记录。内容摘要不是脚本安全证明或系统沙箱。

S05 与 S04 分包相同，根包只保留 `TodoMain`，`HooksMain` 移入 `app` 并提供 `createHooks`；新增 `s05.todo`：`TodoStatus`、`TodoItem`、`TodoList`、`TodoWriteTool`、`TodoReminder`。清单只在会话内存中整体替换，失败不改状态；`todo_write` 免权限确认但仍经过 Hook。提醒附加到本批最后一个工具结果，不新增消息角色，上限批次不附加。Hook 配置固定为 `s05/.agents/hook.json`。

S06 根包只保留 `SubagentMain`，`TodoMain` 移入 `app`；新增 `s06.subagent`：`SubagentRunner`、`TaskTool`。子代理每次 `task` 新建历史，不复制主历史，只返回最终总结；只有五个基础工具，不能递归派生、不改主清单。`task` 免权限确认，子代理工具调用仍经过同一权限与 Hook 链；`ChatLoop` 不修改。Hook 配置固定为 `s06/.agents/hook.json`。

S07 根包只保留 `SkillMain`，`SubagentMain` 移入 `app`；新增 `s07.skill`：`SkillFile`、`Skill`、`SkillCatalog`、`LoadSkillTool`。技能固定从 `s07/.agents/skills/<name>/SKILL.md` 扫描，name 与目录名一致；系统提示词只放名称和描述，正文由 `load_skill` 按需读取、每次重读。无效技能告警跳过不阻止启动，无技能时提示词与 S06 相同。`load_skill` 免权限确认但经过 Hook；子代理也有 `load_skill`，仍无 `task`/`todo_write`；`ChatLoop` 不修改。Hook 配置固定为 `s07/.agents/hook.json`。

S08 根包只保留 `CompactMain`，`SkillMain` 移入 `app`；新增 `s08.compact`：`MicroCompact`、`TranscriptStore`、`ContextCompactor`、`CompactTool`。`ChatLoop` 仅增加可选压缩器，为 null 时与 S07 相同。微压缩只改发送内容；超过阈值或 `compact` 登记时先写工作目录 `.transcripts/` 完整记录，写入失败不总结；摘要后保留原系统提示词、任务清单原文与最新用户输入原文。总结请求不计调用上限、不经过 Hook，失败保留历史。`compact` 免权限确认但经过 Hook，本批结果补齐后才执行；子代理不压缩。Hook 配置固定为 `s08/.agents/hook.json`。

S09 根包只保留 `MemoryMain`，`CompactMain` 移入 `app`；新增 `s09.memory`：`MemoryType`、`MemoryEntry`、`MemoryStore`、`MemoryReadTool`、`MemoryWriteTool`、`MemoryDeleteTool`。记忆为工作目录 `.memory/<name>.md`，文件是唯一数据源；启动时索引（名称、类型、描述）进系统提示词，本次写入下次启动生效，正文由 `memory_read` 每次重读。`memory_read` 免确认，`memory_write`/`memory_delete` 逐次确认，均经过 Hook；子代理无记忆工具；`ChatLoop` 不修改。Hook 配置固定为 `s09/.agents/hook.json`。

S10 根包只保留 `TaskMain`，`MemoryMain` 移入 `app`；新增 `s10.task`：`TaskStatus`、`TaskRecord`、`TaskStore`、`TaskTools`。任务为工作目录 `.tasks/task_xxxxxxxx.json`，文件是唯一数据源，每次调用重读；`blockedBy` 依赖校验存在、非自身、无环，全部完成才能 claim，只有 owner 能 complete，owner 由程序固定为 `agent`。任务内容不进系统提示词，只附规则，用 `task_list` 恢复。六个 `task_*` 工具免确认但经过 Hook；子代理无任务工具；`ChatLoop` 不修改。Hook 配置固定为 `s10/.agents/hook.json`。

S11 根包只保留 `BackgroundMain`，`TaskMain` 移入 `app`；新增 `s11.background`：`BackgroundStatus`、`BackgroundTask`、`BackgroundManager`、`BackgroundTools`。`background_run` 在守护线程用独立 ShellTool（`background.timeout-seconds`，默认 600）执行并立即返回 `bg_N`，同时最多 4 个、每会话 20 个；完成通知进线程安全队列，`ChatLoop` 每次请求模型前取出，附加到最后一条 user/tool 消息末尾，不新增角色，只送达一次，首次请求失败放回。`ChatLoop` 仅增加可选 BackgroundManager，为 null 时与 S10 相同；会话结束取消运行中的命令。`background_run` 与 shell 同规则逐次确认，`background_check` 免确认，均经过 Hook；子代理无后台工具。Hook 配置固定为 `s11/.agents/hook.json`。

S12 根包只保留 `ScheduleMain`，`BackgroundMain` 移入 `app`；新增 `s12.schedule`：`CronExpression`、`ScheduleRunStatus`、`ScheduleRecord`、`ScheduleStore`、`ScheduleManager`、`ScheduleTools`。计划为工作目录 `.schedules/schedule_xxxxxxxx.json`，文件是唯一数据源，每次操作重读，临时文件原子替换；最多 50 个，根 `.gitignore` 忽略 `.schedules/`。五段 cron（分 时 日 月 周）的解析、校验与匹配委托 Hutool `CronPattern`；本类只限制五段，并在日与周同时受限时按“或”匹配，按本机时区解释，最短一分钟。`scheduler.poll-seconds` 默认 15；单守护线程轮询，到点交给 BackgroundManager 执行，命令超时仍用 `background.timeout-seconds`。计划只在进程运行期间触发，重启不补跑错过的时点；启动时把遗留 `running` 修复为 `cancelled`。同一计划上一次未结束时跳过本次时点；定时运行共享后台同时最多 4 个的上限，不占每会话 20 个用户任务额度，不出现在 `background_check`，结果不进入 `<background-results>`，完成状态和输出写回计划文件（输出截断到 2000 字符，终态记录最多保留 20 条）。六个 `schedule_*` 工具中，`schedule_create` 与 shell 共用硬拒绝并逐次确认，`schedule_list`/`schedule_get`/`schedule_pause` 免确认，`schedule_resume`/`schedule_delete` 逐次确认，均经过 Hook；子代理无定时工具；`ChatLoop` 不修改。Hook 配置固定为 `s12/.agents/hook.json`。

S13 根包只保留 `TeamMain`，`ScheduleMain` 移入 `app`；新增 `s13.team`：`TeamMessage`、`MessageBus`、`TeamConsole`、`TeammatePermission`、`TeammateSetup`、`TeammateAgent`、`Teammate`、`TeamManager`、`TeamTools`、`PrefixWriter`。团队模式由 `config.properties` 的 `team.enabled` 开关，默认 false，关闭时 `TeamMain` 直接组装 S12 会话（无团队规则与工具、不清空收件箱、不退回任务）。主线程运行 Lead，是唯一读取用户输入的线程：终端行、Lead 收件箱唤醒和队友确认请求进同一事件队列，`ChatLoop` 不修改。队友是命名守护线程上的完整 Agent，独立历史、清单与压缩器，最多 4 个；没有后台、定时、记忆写入与团队管理工具，不能嵌套组队。收件箱为工作目录 `.mailboxes/<name>.jsonl`，读取后删除，唤醒靠 Condition，启动时清空，根 `.gitignore` 忽略。`TaskStore` 修改在进程内锁加 `.tasks/.lock` 文件锁内完成；队友一次只持有一个任务，空闲时 `claimNext` 自动认领，退出时退回，启动时退回非 `agent` 的进行中任务。`spawn_teammate` 逐次确认，`send_message`/`list_teammates`/`shutdown_teammate` 免确认，均经过 Hook；队友的 ASK 交给主线程询问，会话结束先拒绝待定确认再停止队友。关闭响应按 requestId 与发送者配对。可选 `WorktreeManager`：工作目录为 git 仓库根时，`worktree=true` 为每个队友建 `.worktrees/<name>`（分支 `wt/<name>`），队友工具以它为根，共享存储仍在主目录；条件不满足报错，不退回共用目录；测试用临时仓库，无 git 时跳过。Hook 配置固定为 `s13/.agents/hook.json`。

S14 根包只保留 `McpMain`，`TeamMain` 移入 `app`；新增 `s14.mcp`：`McpConfig`、`McpTrustSnapshot`、`McpTrustStore`、`McpClient`、`McpTool`、`McpManager`。配置固定为 `s14/.agents/mcp.json`（`mcpServers`，只支持 stdio，最多 8 个），缺失时与 S13 完全相同；格式错误阻止启动，单个 server 失败告警跳过。command/args 直接交给 ProcessBuilder，不经过 Shell，工作目录为 S14 子项目；env 值可用 `${NAME}` 引用进程环境变量，确认时只显示变量名。启动前经过独立的 MCP 信任确认（y/once/其余及 EOF 不启动），记录在用户目录 `.java-agent/mcp-trust/`，与 Hook 信任分开，测试使用临时存储。McpClient 逐行收发 JSON-RPC：initialize（2025-06-18）→ initialized → tools/list 分页；调用超时发送 cancelled，断开不重连，关闭先关 stdin 再结束进程树。工具名 `mcp__<server>__<tool>`，与内置工具一样经过 Hook 与权限：默认 ASK，mcp.json `allow` 中的工具 ALLOW。只有主 Agent（团队模式为 Lead）有 MCP 工具，子代理与队友没有；`ChatLoop` 与 `ToolRegistry` 不修改。测试使用独立 JVM 运行的 `FakeMcpServer`，不依赖 Python。Hook 配置固定为 `s14/.agents/hook.json`。

## 构建与测试

JDK 17、Maven；工具类优先 Hutool，JSON 使用 fastjson2。JUnit 5 版本固定在父 POM。

根目录 `mvn test` / `mvn package` 构建全部；`mvn -pl s02 test` 只测第二课；`mvn -pl s04 -q compile exec:java` 启动第四课。Windows 使用 `./mvnw.cmd` 替代 `mvn`。子工程内可用 `mvn test` 或 `../mvnw.cmd test`，不需先安装其他课程。

程序从进程工作目录读 `config.properties`，相对工具目录也以此为准。根目录启动共用根配置，子工程内启动用自己的配置。所有 config.properties 与 target/ 均忽略，不提交密钥。

自动测试无需 API 密钥，HTTP 协议使用本地模拟服务；真实服务商验证单独记录。危险命令仅作为权限分类字符串；真实执行使用临时目录和无副作用命令。各模块保留完整阶段测试，不能因为其他模块测试过而删除本模块关键回归。

## 编码规范

UTF-8；四空格缩进；类名 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE，包名小写。测试类命名 `*Test.java`。增加功能覆盖关键行为和错误路径。结构或父 POM 改动需全量构建及独立入口验证。

## 提交

主题简短、可检索，一次聚焦一项改动，例如 `feat: add hooks`、`refactor: split lesson modules`。记录目的、模块差异和验证结果，不夹带用户其他学习笔记与素材的未提交改动。旧课次记录只作历史，当前范围与命令以 README 和实战规范为准。
