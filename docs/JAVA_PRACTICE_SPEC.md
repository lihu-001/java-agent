# Java Agent 实战规范

当前采用十一个独立 Maven 子工程。课次边界以用户重新定义为准：S01 最小文本对话 Loop，不含工具；S02 工具及多工具；S03 权限；S04 Hook；S05 TodoWrite；S06 Subagent；S07 Skill Loading；S08 Context Compact；S09 Memory；S10 Task System；S11 Background Tasks。

## 模块边界

| 模块 | 包与默认入口 | 范围 |
| --- | --- | --- |
| s01 | dev.learnagent.s01 / AgentLoopMain | 6 个生产类，输入、模型请求、文本消息与历史 |
| s02 | dev.learnagent.s02 / ToolUseMain | 在对话基础上增加 Shell、文件工具、分派与回传 |
| s03 | dev.learnagent.s03 / PermissionMain | 在工具分派前增加许可策略和逐次确认 |
| s04 | dev.learnagent.s04 / HooksMain | 四类生命周期 Hook、JSON 命令配置与 Java 回调 |
| s05 | dev.learnagent.s05 / TodoMain | todo_write 会话内任务清单与未更新提醒 |
| s06 | dev.learnagent.s06 / SubagentMain | task 子代理，独立消息历史，只回传总结 |
| s07 | dev.learnagent.s07 / SkillMain | 技能目录与 load_skill 按需加载 |
| s08 | dev.learnagent.s08 / CompactMain | 微压缩、阈值自动总结、compact 工具与完整记录 |
| s09 | dev.learnagent.s09 / MemoryMain | `.memory/` 跨会话记忆、索引进系统提示词、读写删工具 |
| s10 | dev.learnagent.s10 / TaskMain | `.tasks/` 持久化任务图、blockedBy 依赖、认领与完成 |
| s11 | dev.learnagent.s11 / BackgroundMain | 后台命令、完成通知在下次请求前自动送达 |

每课是前一课的源码快照加增量修改，拥有独立源码、测试、POM、配置示例、README。各模块不依赖其他课的 JAR 或源码，不使用共用 Java 核心。根 POM 为 packaging=pom，只聚合各课、管理依赖和插件版本。

这是教学上的有意重复，替代拆分前共用同一循环的设计。每个模块 README 列出相较前一课的新增类与改动点，后续课不能反向改变前一课的可读内容。

## 课程实现约定

S01 的 ChatMessage 只有 role/content；ModelClient 接收消息并返回文本，HTTP 请求无 tools/tool_choice。每次有效输入仅请求一次模型，失败不记录本轮，无工具执行器或 Shell 配置。

S02 增加 AgentTool、ToolDefinition、ToolCall、ToolRegistry、Shell 和四个文件工具，模型客户端支持 tool_calls。循环保存 assistant，执行并补齐每个 ID 的 tool 结果，再请求模型。错误也回传；请求次数、Shell 超时和输出长度有限制。没有权限或 Hook 类。

S03 新增 ToolPermission、PermissionPolicy、PermissionGate、PermissionMain。读和查找默认允许，写/编辑/Shell 逐次确认，越界和示例硬拒绝直接阻止。确认不进入模型历史，批准只针对本次。没有 Hook 类。

S04 新增 hook.AgentHook、hook.AgentHooks、hook.LoggingHook 和根包 HooksMain。四事件为 UserPromptSubmit、PreToolUse、PostToolUse、Stop。两个前置事件可拦截，后置异常不覆盖结果；任务完成仅指本轮正常最终回复，失败、超时、超限、中断和退出不触发 Stop。事件链由本模块循环和工具分派器共用，自定义回调只注册一次。

S04 固定从本子项目的 `.agents/hook.json` 加载用户配置，与启动目录无关，不读取或回退到父工程的 Hook 配置。S04ProjectDirectory 根据本模块编译产物定位源码工程；显式项目目录重载仅供嵌入和隔离测试。配置缺失或空 hooks 时仍执行 Agent 的 Java Hook，配置错误阻止启动。HookConfig 校验四事件、工具名称 matcher 正则、command 类型和 1—300 秒 timeout（默认 30）；CommandHook 生成事件 JSON，HookCommandRunner 通过 stdin 交给命令。命令在 S04 子项目目录同步执行，输出最多保留 12000 字符；前置退出码 0 放行、2 拦截，其他失败也拦截；后置失败告警并保留结果。Windows 使用 PowerShell，Unix 使用 /bin/sh，超时或中断尽力终止进程树。所有 HooksMain.createLoop 重载均按“内置日志 → 追加的 Agent Java 回调 → 用户 JSON 命令”合并注册，两种来源共同生效；传入 Java 列表不会关闭日志或 JSON。详见 [Hook 配置协议](lessons/s04-hooks.md#3-json-配置命令-hook)。

用户命令注册前必须通过 HookTrust 独立信任确认。y/yes 记忆授权，once 只授权本次会话，其他输入或 EOF 不启用用户命令但保留 Java 回调。HookTrustStore 按规范化项目路径在用户目录 `.java-agent/hook-trust/` 存储内容摘要，项目 JSON 不能自行声明信任；记录损坏、缺失或不可读时重新确认，保存失败仅本次有效。HookTrustSnapshot 跟踪配置、命令中可识别的字面量脚本以及 trust_files 声明的普通文件，确认期间及每次执行前校验内容。运行中变化则该会话的用户 Hook 失效，前置阻止、后置告警，重启后重新授权。确认答案不进入模型历史，信任 Hook 不代替 S03 工具审批。这是启用授权和变更检测，不是代码安全分析或系统沙箱。

S05 以 S04 完整源码为起点，新增 todo.TodoStatus、TodoItem、TodoList、TodoWriteTool、TodoReminder 和根包 TodoMain；S04 的 HooksMain 移入 app 并抽出 createHooks，注册顺序不变。todo_write 每次整体替换会话内存清单：最多 20 项，内容去空白后非空且不超过 200 字符，最多一项 in_progress，未知字段或状态拒绝；失败返回工具错误且不改状态。PermissionPolicy 对其直接允许，但仍经过 PreToolUse/PostToolUse。TodoReminder 每轮输入清零，每批工具后按清单版本号计数；连续 3 批未更新且清单为空或有未完成项时，提醒附加到本批最后一个工具结果末尾，不新增消息角色；达到模型调用上限的批次不附加。清单不持久化，不验证步骤真实完成。Hook 配置固定为 `s05/.agents/hook.json`，默认空。详见 [S05 课程](lessons/s05-todo-write.md)。

S06 以 S05 完整源码为起点，新增 subagent.SubagentRunner、TaskTool 和根包 SubagentMain；S05 的 TodoMain 移入 app，ChatLoop 不修改。task 参数为 description（去空白后非空，≤40 字符）与 prompt（≤8000 字符），未知字段拒绝。SubagentRunner 每次新建历史，只含子代理系统提示词与 prompt，不复制主历史；执行工具直到无工具调用的最终回复，返回去空白后的文字，结束即丢弃历史。子代理每次 task 最多请求 agent.max-model-calls 次，达到上限时最后一批不执行，返回未完成说明。结果超过 12000 字符截断；模型请求超时或失败转为工具错误，不回滚已执行操作。子代理只有五个基础工具，无 task（禁止递归）和 todo_write（不改主清单），与主代理共用同一 PermissionGate 和 AgentHooks：task 直接允许，子代理的写入、编辑、Shell 仍逐次确认；PreToolUse/PostToolUse 两层都触发，Hook 拦截 task 时子代理不启动；UserPromptSubmit、Stop 和清单提醒只属于主循环。Hook 配置固定为 `s06/.agents/hook.json`，默认空。详见 [S06 课程](lessons/s06-subagent.md)。

S07 以 S06 完整源码为起点，新增 skill.SkillFile、Skill、SkillCatalog、LoadSkillTool 和根包 SkillMain；S06 的 SubagentMain 移入 app，ChatLoop 不修改。技能固定从 `s07/.agents/skills/<name>/SKILL.md` 扫描，与启动目录无关。frontmatter 只解析单行 key: value（可加引号、# 注释、未知字段忽略、重复字段拒绝），不是完整 YAML；name 为小写字母数字与连字符、≤64 字符且与目录名一致，description 非空 ≤1024 字符，正文非空，文件 ≤64 KiB 严格 UTF-8，链接越出技能目录拒绝。最多 32 个，按目录名排序；单个无效时告警跳过，目录不存在视为无技能，目录不可读阻止启动。系统提示词只追加技能目录（名称与描述）及使用规则，无技能时与 S06 相同。load_skill 参数仅 name，返回 `<skill name="...">正文</skill>`，超过 12000 字符截断；每次重新读文件，正文修改即时生效，增删技能或改名需重启。未知名称为工具错误并列出可用技能。PermissionPolicy 直接允许，仍经过 PreToolUse/PostToolUse。子代理系统提示词同样附带目录并拥有 load_skill，仍无 task 与 todo_write。技能文本不授予权限、不做信任授权。Hook 配置固定为 `s07/.agents/hook.json`，默认空。详见 [S07 课程](lessons/s07-skill-loading.md)。

S08 以 S07 完整源码为起点，新增 compact.MicroCompact、TranscriptStore、ContextCompactor、CompactTool 和根包 CompactMain；S07 的 SkillMain 移入 app。ChatLoop 增加可选 ContextCompactor 构造参数，为 null 时与 S07 相同；每次请求模型前调用。微压缩保留最近 `compact.keep-tool-results`（默认 3）个工具结果原文，更早且超过 200 字符的替换为占位说明，task、load_skill、todo_write、compact 结果不替换；只作用于发送内容，内存历史保留原文。微压缩后对话（不含系统提示词）的粗略字符数超过 `compact.threshold-chars`（默认 50000），或上一批工具中有 compact 登记时：先把内存历史写成工作目录 `.transcripts/transcript-时间.jsonl`（只新建文件，写入失败则不总结），再用独立提示词、不带工具请求总结（输入最多 60000 字符，保留最近部分；摘要最多 8000 字符），历史替换为原系统提示词加一条以 `[对话已压缩]` 开头的 user 消息，其中附记录路径、摘要、程序保存的任务清单原文和最近一条真实用户输入原文。总结请求不计入每轮调用上限、不经过 Hook；失败、空回复或返回工具调用时告警并继续使用未总结的上下文。compact 参数只有可选 focus（≤200 字符），执行时只登记，本批结果补齐后的下一次请求前生效且只生效一次；PermissionPolicy 直接允许，仍经过 PreToolUse/PostToolUse。中途压缩后摘要消息成为本轮起点。子代理不压缩，也没有 compact。字符数不是 token 计数；记录可能含敏感内容，不自动清理，根 `.gitignore` 忽略 `.transcripts/`。Hook 配置固定为 `s08/.agents/hook.json`，技能目录为 `s08/.agents/skills`。详见 [S08 课程](lessons/s08-context-compact.md)。

S09 以 S08 完整源码为起点，新增 memory.MemoryType、MemoryEntry、MemoryStore、MemoryReadTool、MemoryWriteTool、MemoryDeleteTool 和根包 MemoryMain；S08 的 CompactMain 移入 app，ChatLoop 不修改。记忆保存在工作目录 `.memory/<name>.md`，frontmatter 为 name（小写字母数字与连字符、≤64 字符、与文件名一致）、type（user/feedback/project/reference）、description（单行 ≤200 字符），正文非空 ≤4000 字符，文件 ≤64 KiB 严格 UTF-8、不跟随链接，最多 100 条；文件是唯一数据源，没有额外索引文件。启动时扫描一次，按名称排序，单个无效告警跳过，目录不存在视为无记忆，路径不是目录阻止启动；索引（名称、类型、描述）附在 S08 系统提示词与记忆规则之后，无记忆时只附规则。本次会话写入或删除不改动系统提示词，下次启动生效。memory_read 每次重读文件；memory_write 同名整体覆盖，经临时文件替换，新建时检查条数上限；memory_delete 删除普通文件。参数未知字段拒绝。PermissionPolicy：memory_read 直接允许，memory_write 与 memory_delete 逐次确认；三者均经过 PreToolUse/PostToolUse。子代理没有记忆工具和索引。规则声明记忆可能过时、与当前要求冲突时以后者为准、不能据此跳过权限。根 `.gitignore` 忽略 `.memory/`。Hook 配置固定为 `s09/.agents/hook.json`，技能目录为 `s09/.agents/skills`。详见 [S09 课程](lessons/s09-memory.md)。

S10 以 S09 完整源码为起点，新增 task.TaskStatus、TaskRecord、TaskStore、TaskTools 和根包 TaskMain；S09 的 MemoryMain 移入 app，ChatLoop 不修改。任务保存在工作目录 `.tasks/task_xxxxxxxx.json`（`task_` 加 8 位随机小写十六进制，排他创建，冲突重试），字段只有 id、subject（单行 ≤200 字符）、description（≤4000 字符）、status（pending/in_progress/completed）、owner（pending 时为 null，其余必填，≤64 字符）、blockedBy（≤20 个其他任务 ID）；文件 ≤64 KiB 严格 UTF-8、不跟随链接、id 与文件名一致，最多 200 个。文件是唯一数据源，每次调用重读，修改经临时文件替换；无效文件告警跳过，目录不存在视为无任务，路径不是目录报错。task_update 只对 pending 任务追加依赖，整次校验存在、非自身、不成环后才保存，重复依赖去重；task_claim 要求 pending 且依赖全部 completed（缺失或无效算未完成）；task_complete 要求 in_progress 且 owner 一致，返回本次完成后依赖全部完成、且依赖本任务的 pending 下游。owner 由程序固定为 `agent`，工具没有 owner 参数；参数未知字段拒绝。任务内容不进系统提示词，S09 提示词后附 TASK_RULE；启动时终端打印任务数与已完成数。PermissionPolicy 对六个任务工具直接允许，均经过 PreToolUse/PostToolUse。子代理没有任务工具。根 `.gitignore` 忽略 `.tasks/`。Hook 配置固定为 `s10/.agents/hook.json`，技能目录为 `s10/.agents/skills`。详见 [S10 课程](lessons/s10-task-system.md)。

S11 以 S10 完整源码为起点，新增 background.BackgroundStatus、BackgroundTask、BackgroundManager、BackgroundTools 和根包 BackgroundMain；S10 的 TaskMain 移入 app。ChatLoop 增加可选 BackgroundManager 构造参数，为 null 时与 S10 相同。background_run 用独立 ShellTool（工作目录与输出上限同 shell，超时读 `background.timeout-seconds` 默认 600 秒）在守护线程执行，立即返回 `bg_N`；同时最多 4 个、每次会话最多 20 个，超出返回工具错误，参数只有 command，未知字段拒绝。状态由 Shell 结果判定：`退出码：0` 为 completed，命令超时为 timed_out，其他与启动异常为 failed；会话结束时取消运行中的任务为 cancelled。每次请求模型前，ChatLoop 取出通知队列中的全部通知，合并为 `<background-results>…</background-results>` 附加到当前最后一条消息末尾；该消息只可能是本轮 user 输入或本批最后一个 tool 结果，不新增消息角色，tool_call_id 不变，每条只送达一次，单条输出超过 2000 字符截断。本轮第一次请求失败时已取出的通知放回队首，下一轮再送达；工具执行后的失败保留历史，通知随之保留。后台线程完成时另在终端打印一行，程序不会为通知主动发起模型请求。background_check 省略 ID 列出任务，给出 ID 返回状态与完整输出。PermissionPolicy：background_run 与 shell 共用硬拒绝和逐次确认，确认只在启动时进行，background_check 直接允许；两者均经过 PreToolUse/PostToolUse，命令真正结束时不触发 Hook。子代理没有后台工具。ChatLoop.run 的 finally 在 exit、EOF、中断或异常时调用 shutdown，取消仍在运行的命令并打印数量；任务只在内存中，不跨会话。系统提示词在 S10 基础上追加后台规则。Hook 配置固定为 `s11/.agents/hook.json`，技能目录为 `s11/.agents/skills`。详见 [S11 课程](lessons/s11-background-tasks.md)。

保留前一课的组装类便于代码对照；启动应使用各模块 POM 的默认入口。例如 s04 默认 HooksMain，直接手动运行保留的 app.ToolUseMain 不会启用权限和 Hook。

## 技术与目录

JDK 17、Maven Wrapper 3.9.3、Hutool、fastjson2、JUnit 5，版本集中管理。各模块有 src/main/java 和 src/test/java，包名与模块名对应。S02—S04 各有 playground，根目录也保留已有用户练习文件。

S04 按职责分包，根包只留 HooksMain：app 放历史入口与组装，config 放配置，loop 放循环，model 放消息与模型客户端，tool 放接口/调用数据/注册表/Shell，tool.file 放文件工具与内部辅助类，permission 放权限，hook 放钩子。测试同步使用对应子包。模块之间仍没有 Java 依赖，S01—S03 的阶段源码不因 S04 整理而修改。

配置只从当前进程工作目录的 config.properties 读取，UTF-8；从根目录执行 -pl 共用根配置，进入子工程运行使用子工程配置。所有层级的 config.properties 和 target/ 被忽略，不把真实凭据复制进新模块。S01 忽略工具配置项。

```powershell
# 全部模块
.\mvnw.cmd clean package

# 单独选择模块，无需安装其他课程
.\mvnw.cmd -pl s01 test
.\mvnw.cmd -pl s02 -q compile exec:java
.\mvnw.cmd -pl s03 -q compile exec:java
.\mvnw.cmd -pl s04 -q compile exec:java

# 在子工程目录内
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

## 测试要求

每个模块保留该阶段完整回归，不因其他课已有测试而省略。S01 验证纯文本协议与历史，S02 增加工具及本地 HTTP 集成，S03 增加权限，S04 增加四事件生命周期测试。自动测试使用模拟模型、本地 HTTP 和临时文件，不需要 API 密钥。

Shell 危险命令只作为待分类字符串；真实执行用无副作用命令。工作区与文本权限规则不是系统沙箱。Unix 分支未在本机验证，Windows 符号链接测试可能因权限跳过。真实模型验收必须单独记录。

## 后续课程

S12 Cron Scheduler、S13 Agent Teams、S14 MCP Plugin、S15 Integrated Harness、S16 Workflow Runtime、S17 Goal Loop。新增课以当前前一课的完整源码为起点，保留已完成课程快照。

## 模块拆分验证

拆分前已提交 `231f8c0`，包含四类 Hook。旧工程的构建与真实模型记录归档在 [拆分前规范](history/pre-modules-spec.md)。新课次定义与旧记录不同，不能用旧记录替代新模块验收。

本次将最早的纯文本循环恢复为 S01，并保留对截断/过滤响应的错误处理；S02、S03、S04 分别取各阶段完整实现，统一为本模块包名。根工程不再保留生产或测试源码。

2026-10-20，Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。执行 `.\mvnw.cmd clean package`，父工程及四个模块全部 `BUILD SUCCESS`，分别生成四个普通 JAR：

| 模块 | 测试总数 | 通过 | 跳过 |
| --- | --- | --- | --- |
| S01 | 24 | 24 | 0 |
| S02 | 102 | 101 | 1 |
| S03 | 154 | 153 | 1 |
| S04 | 190 | 189 | 1 |
| 合计 | 470 | 467 | 3 |

没有失败或错误。三个跳过均为各课程快照中的同一类 Windows 符号链接权限测试，不能记作通过。完整阶段测试在各模块重复是有意设计。

另外验证：

1. 根目录分别执行 `-pl s01` 至 `-pl s04` 的 `exec:java`，输入 `exit`，全部正常启动与退出。S01 无工具列表，其余模块各显示五个工具；S04 显示四事件。
2. 进入每个子工程，分别执行 `..\mvnw.cmd -q -DskipTests package` 和 `..\mvnw.cmd -q exec:java`，独立打包与启动均成功，无需安装其他课程。此步骤只验证独立构建，测试结果来自前述全量构建。
3. 子工程启动验证使用新建的临时假配置，输入只有 exit，没有请求外部模型；验证后已删除假配置，未复制或改写根目录真实配置。
4. 检查所有 Java 文件的包和引用，仅属于所在课次；S01 仅六个生产类，S01/S02 无权限类，S01—S03 无 Hook 类；POM 没有跨课程依赖。
5. 所有层级的 target/ 与 config.properties 均正确忽略。各模块文档和配置示例使用 UTF-8。

本次未重新请求真实模型服务，旧工程的真实验收只作历史证据。拆分前的保存点为 `231f8c0`。

## S04 职责分包验证

2026-10-20，将 S04 的 25 个生产类和 14 个测试类迁移到职责子包，根包只保留 HooksMain。更新跨包导入；仅将 WorkspaceFiles.root() 公开给 app 组装层，文件读写与参数解析辅助方法仍为包内可见。默认入口和启动命令不变，S01—S03 源码未修改。

清理 S04 旧编译产物后完成测试，再运行根目录 `.\mvnw.cmd package`，四模块全部成功；仍为 470 项、467 通过、3 项符号链接权限跳过。S04 为 190 项、189 通过、1 跳过。根目录 `-pl s04 -q exec:java` 输入 exit 正常启动并退出，显示四事件及五工具；没有请求真实模型服务。测试与文档引用已同步迁移到新包。

## S04 JSON 命令 Hook 验证

2026-10-20，增加 HookConfig、CommandHook、HookCommandRunner，以及 S04 子项目下的配置与完整示例。默认固定加载 `s04/.agents/hook.json`，命令以 S04 为工作目录；示例脚本使用 Python，核心运行与 JUnit 测试不依赖 Python。

根目录 `.\mvnw.cmd -B package` 成功：共 522 项测试，519 通过、3 项 Windows 符号链接权限跳过，无失败或错误。其中 S04 共 242 项，241 通过、1 跳过；新增的 52 项均通过，覆盖配置校验、四事件 JSON 传递、匹配顺序、拒绝和权限衔接、后置隔离、超时中断、输出限制及子项目定位。包含 4 项组合回归：Java 与 JSON 在四事件同时执行且日志不重复、Java 拒绝阻止用户命令、Java 放行后用户仍可拒绝、Java 后置失败不阻止用户回调；6 项定位测试覆盖 Maven/IDEA 路径、中文空格路径及禁止回退到父工程配置。

根目录启动时日志确认读取 `s04/.agents/hook.json`，输入 exit 正常退出。另从无关临时目录启动，放置假模型配置及故意无效的本地 Hook 配置，确认程序忽略启动目录 Hook 配置，仍加载 S04 的用户配置、执行 Python 脚本并拦截“取消”，没有请求模型。临时目录已清理；IDEA 产物路径通过单元测试模拟，未操控 IDE。没有请求真实模型服务；Unix Shell 分支未在本机执行。

## S04 用户 Hook 信任验证

2026-10-20，加入 HookTrust、HookTrustStore、HookTrustSnapshot；保留内置 Java Hook，用户命令单独授权。全量 `.\mvnw.cmd -B package` 通过：545 项测试、542 通过、3 项 Windows 符号链接权限跳过，无失败或错误；S04 共 265 项、264 通过、1 跳过。

新增 19 项信任流程测试及 4 项配置校验测试，覆盖拒绝/EOF、单次授权、记忆授权与项目隔离、配置变更、脚本内容变化但大小/时间戳不变、显式依赖、缺失文件、坏记录、存储读写故障、确认过程中变化、运行中失效，以及 Java 回调与 S03 工具审批独立。所有信任记录使用临时目录，不读写真实用户记录。

实际 HooksMain 入口在独立 JVM、临时 user.home 和假模型配置下完成五种离线验证：拒绝、仅本次、信任并保存、下一次免确认、删除记录后重新确认。使用 S04 当前示例脚本拦截“取消”，没有请求模型；临时用户目录已清理。用户当前 hook.json 未修改。

## S05 TodoWrite 验证

2026-10-21，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S04 为 s05 并重命名包，新增 todo 包与 TodoMain，父 POM 聚合 s05。根目录 `.\mvnw.cmd -B package` 五模块全部成功：共 836 项测试，832 通过、4 项 Windows 符号链接权限跳过，无失败或错误。S01—S04 分别为 24、102、154、265 项，与上次一致；S05 共 291 项、290 通过、1 跳过，其中新增 26 项（另在权限策略测试中补充 todo_write 断言）覆盖清单校验与失败不改状态、工具参数、提醒计数与每轮清零、提醒附加位置及消息顺序、跨轮保留、上限批次不提醒、Hook 拦截、todo_write 免确认而写文件仍需确认。

根目录 `-pl s05 exec:java` 与 `-pl s04 exec:java` 输入 exit 正常启动退出，S05 显示六个工具并读取 `s05/.agents/hook.json`。S01—S04 源码未修改。没有请求真实模型服务；模型是否主动使用清单需真实服务单独验收。

## S06 Subagent 验证

2026-10-21，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S05 为 s06 并重命名包，新增 subagent 包与 SubagentMain，父 POM 聚合 s06。根目录 `.\mvnw.cmd -B package` 六模块全部成功：共 1143 项测试，1138 通过、5 项 Windows 符号链接权限跳过，无失败或错误。S01—S05 分别为 24、102、154、265、291 项，与上次一致；S06 共 307 项、306 通过、1 跳过，其中新增 16 项（另在权限策略测试中补充 task 断言）覆盖子代理全新且互相独立的历史、只返回最终文字、上限时不执行最后一批、空回复与请求失败、task 参数校验与结果截断、主代理看不到子代理读到的内容、子代理工具不含 task/todo_write、子代理写入仍需确认、两层 Hook 顺序、Hook 拦截 task 时子代理不启动、子代理不能改动主清单。

根目录 `-pl s06 exec:java` 正常启动，显示七个工具并读取 `s06/.agents/hook.json`。PowerShell 管道输入的 exit 被记为 5 字符（推测带 BOM），未被识别为退出命令，作为普通提示词向根配置的真实模型请求了一次，模型直接回复且无工具调用，Hook 与 Stop 正常触发，EOF 后退出。S01—S05 源码未修改。模型是否在合适时机主动派发子代理需真实服务单独验收。

## S07 Skill Loading 验证

2026-10-21，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S06 为 s07 并重命名包，新增 skill 包与 SkillMain，父 POM 聚合 s07。根目录 `.\mvnw.cmd -B package` 七模块全部成功：共 1472 项测试，1465 通过、7 项跳过，无失败或错误。S01—S06 测试未改动；S07 共 329 项、327 通过、2 跳过（原有的 Windows 符号链接权限测试，以及新增的技能链接越界测试），新增 22 项（另在权限策略测试中补充 load_skill 断言）覆盖 frontmatter 解析与错误格式、目录缺失、排序与只渲染元数据、无效技能跳过、数量上限、正文每次重读与改名报错、示例技能有效、load_skill 参数校验与截断、系统提示词只含目录而正文作为工具结果、免确认但经过 Hook、Hook 拦截、子代理可加载技能且仍无 task、无技能时提示词与 S06 相同。

根目录 `-pl s07 exec:java` 以空输入（EOF）启动并退出，显示八个工具和两个示例技能，读取 `s07/.agents/skills` 与 `s07/.agents/hook.json`；未请求模型。S01—S06 源码未修改。模型是否在合适时机主动加载技能需真实服务单独验收；符号链接越界拒绝在本机因权限跳过，未实际验证。

## S08 Context Compact 验证

2026-10-21，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S07 为 s08 并重命名包，新增 compact 包与 CompactMain，父 POM 聚合 s08。根目录 `.\mvnw.cmd -B package` 八模块全部成功：共 1824 项测试，1815 通过、9 项跳过，无失败或错误。S01—S07 源码与测试未改动，沿用上次的 1472 项；S08 共 352 项、350 通过、2 跳过（与 S07 相同的两项符号链接测试），新增 23 项（另在配置和权限策略测试中补充压缩配置与 compact 断言）覆盖微压缩替换范围与豁免工具、发送内容与内存历史分离、阈值总结与完整 JSONL 记录、摘要附加任务清单与最新用户输入、compact 登记与 focus、参数校验、总结失败/返回工具调用/记录无法保存时保留历史、无可总结内容跳过、重复压缩、一轮中途压缩后协议合法且下一轮基于摘要、总结请求不计入调用上限、compact 免确认经过 Hook 且可被拦截。

根目录 `-pl s08 exec:java` 以空输入（EOF）启动并退出，显示九个工具、两个示例技能、完整记录目录与 `s08/.agents/hook.json`；未请求模型。摘要质量及压缩后模型能否继续任务需真实服务单独验收。

## S09 Memory 验证

2026-10-21，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S08 为 s09 并重命名包，新增 memory 包与 MemoryMain，父 POM 聚合 s09。根目录 `.\mvnw.cmd -B clean package` 九模块全部成功：共 2196 项测试，2185 通过、11 项跳过，无失败或错误。S01—S08 源码与测试未改动，沿用上次的 1824 项；S09 共 372 项、370 通过、2 跳过（与 S08 相同的两项符号链接测试），新增 20 项（另在权限策略测试中补充三个记忆工具断言）覆盖目录缺失、写入排序与索引只含元数据、文件格式、整体覆盖不留临时文件、手动修改即时生效、删除、名称校验含路径穿越、描述与正文校验、无效文件跳过、数量上限、工具参数校验，以及第一次会话确认写入、第二次会话索引进入系统提示词且正文按需读取、拒绝写入不落盘、删除需确认、Hook 顺序与拦截、子代理无记忆工具。

根目录 `-pl s09 exec:java` 以空输入（EOF）启动并退出，显示十二个工具、两个示例技能、记忆索引 0 条与 `s09/.agents/hook.json`；未请求模型。模型是否在合适时机主动保存、读取和更新记忆需真实服务单独验收。

## S10 Task System 验证

2026-10-22，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S09 为 s10 并重命名包，新增 task 包与 TaskMain，父 POM 聚合 s10。根目录 `.\mvnw.cmd -B clean package` 十模块全部成功：共 2588 项测试，2575 通过、13 项跳过，无失败或错误。S01—S09 源码与测试未改动，沿用上次的 2196 项；S10 共 392 项、390 通过、2 跳过（与 S09 相同的两项符号链接测试），新增 20 项（另在权限策略测试中补充六个任务工具断言）覆盖目录缺失、创建格式、标题与描述校验、依赖去重与整次校验（自身、环、缺失时不保存）、认领需依赖完成与状态约束、只有认领者能完成、完成后返回刚解锁的下游、新实例从文件恢复与渲染、缺失依赖算未完成、无效文件跳过、手动修改即时生效、不留临时文件、ID 校验，六个工具的参数校验与生命周期，以及第一次会话两阶段建图与阻塞认领、第二次会话 task_list 恢复且任务内容不进系统提示词、免确认、Hook 顺序与拦截、子代理无任务工具、启动报告无效文件。

根目录 `-pl s10 exec:java` 以空输入（EOF）启动并退出，显示十八个工具、两个示例技能、记忆索引 0 条、任务 0 个与 `s10/.agents/hook.json`；未请求模型。模型是否主动拆分任务、按依赖推进和跨会话恢复需真实服务单独验收。

## S11 Background Tasks 验证

2026-10-22，Windows、JDK 17.0.2、Maven Wrapper 3.9.3。复制 S10 为 s11 并重命名包，新增 background 包与 BackgroundMain，父 POM 聚合 s11。根目录 `.\mvnw.cmd -B clean package` 十一模块全部成功：共 3003 项测试，2988 通过、15 项跳过，无失败或错误。S01—S10 源码与测试未改动，沿用上次的 2588 项；S11 共 415 项、413 通过、2 跳过（与 S10 相同的两项符号链接测试），新增 23 项（后台包 22 项与配置 1 项，另在权限策略测试中补充 background_run 的逐次确认、硬拒绝与缺少命令，以及 background_check 直接允许的断言）覆盖：启动立即返回且完成后只通知一次、状态按退出码与超时判定、乱序完成按完成顺序通知、放回队列后顺序不变、并发与总数上限、Shell 异常转为失败、未知 ID、关闭后取消且不通知并拒绝新任务、长输出在通知中截断而 background_check 保留全文、真实 PowerShell 后台执行与超时、退出会话不等待长命令且命令副作用未发生；后台工具参数校验与查看；主循环注册二十个工具、系统提示词为 S10 加后台规则、通知附加到最后一条 user/tool 消息且不新增角色、只出现一次、首次请求失败后放回下一轮送达、拒绝确认不启动、硬拒绝生效、exit 取消运行中的命令、Hook 顺序与拦截、子代理无后台工具；`background.timeout-seconds` 默认值与非法值。并发测试用可控的假 Shell 决定结束时机，不依赖真实等待。

根目录 `-pl s11 exec:java` 以空输入（EOF）启动并退出，显示二十个工具、两个示例技能、记忆索引 0 条、任务 0 个与 `s11/.agents/hook.json`；未请求模型。模型是否在合适时机选择后台执行、等待期间是否继续做有用的事需真实服务单独验收；Unix Shell 分支未在本机执行。
