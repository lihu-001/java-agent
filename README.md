# Java Agent 逐课实战

十一个独立 Maven 子工程，每课保存该阶段完整源码和测试。后续课程以前一课为基础修改、扩展，模块之间没有 Java 依赖；修改 S11 不会改变 S01—S10。根 POM 只聚合构建并统一 JDK、依赖和插件版本。

| 模块 | 本课能力 | 相较上一课新增 | 默认入口 |
| --- | --- | --- | --- |
| [s01](s01/README.md) | 最小文本对话 Loop | 输入、请求、历史、退出和错误恢复；没有工具 | `dev.learnagent.s01.AgentLoopMain` |
| [s02](s02/README.md) | 单工具与多工具调用 | Shell、四个文件工具、分派和工具结果回传 | `dev.learnagent.s02.ToolUseMain` |
| [s03](s03/README.md) | 工具权限处理 | ALLOW / DENY / ASK、逐次确认 | `dev.learnagent.s03.PermissionMain` |
| [s04](s04/README.md) | 四类 Hook | 四事件回调、JSON 配置及外部命令 | `dev.learnagent.s04.HooksMain` |
| [s05](s05/README.md) | TodoWrite 任务清单 | `todo_write` 工具、会话内清单、未更新提醒 | `dev.learnagent.s05.TodoMain` |
| [s06](s06/README.md) | Subagent 子代理 | `task` 工具、独立消息历史、只回传总结 | `dev.learnagent.s06.SubagentMain` |
| [s07](s07/README.md) | Skill Loading 技能加载 | 技能目录、`load_skill` 工具、正文按需读取 | `dev.learnagent.s07.SkillMain` |
| [s08](s08/README.md) | Context Compact 上下文压缩 | 微压缩、阈值自动总结、`compact` 工具、完整记录 | `dev.learnagent.s08.CompactMain` |
| [s09](s09/README.md) | Memory 跨会话记忆 | `.memory/` 记忆文件、索引进系统提示词、`memory_read` / `memory_write` / `memory_delete` | `dev.learnagent.s09.MemoryMain` |
| [s10](s10/README.md) | Task System 持久化任务 | `.tasks/` 任务图、`blockedBy` 依赖、六个 `task_*` 工具 | `dev.learnagent.s10.TaskMain` |
| [s11](s11/README.md) | Background Tasks 后台任务 | `background_run` / `background_check`、后台线程执行、完成通知自动送达 | `dev.learnagent.s11.BackgroundMain` |

每课的全部 Java 类位于自己的 `dev.learnagent.s0N` 包及子包，阅读时无需跳到其他模块。S04 已按配置、循环、模型、工具、权限和 Hook 分包，结构见 [S04 README](s04/README.md)。重复源码是用于对照学习的阶段快照，没有额外 common 模块。

## 目录

```text
pom.xml                         # 父 POM：聚合构建、版本管理
mvnw / mvnw.cmd / .mvn/          # 共用 Maven Wrapper
s01/                            # 最小对话，6 个生产类
s02/                            # 基于 S01 增加工具
s03/                            # 基于 S02 增加权限
s04/                            # 基于 S03 增加 Hook
s05/                            # 基于 S04 增加 TodoWrite
s06/                            # 基于 S05 增加 Subagent
s07/                            # 基于 S06 增加 Skill Loading
s08/                            # 基于 S07 增加 Context Compact
s09/                            # 基于 S08 增加 Memory
s10/                            # 基于 S09 增加 Task System
s11/                            # 基于 S10 增加 Background Tasks
  pom.xml                       # 各模块都有独立 POM
  README.md                     # 范围、增量说明和阅读顺序
  config.example.properties     # 配置示例
  src/main/java/dev/learnagent/s04/
  src/test/java/dev/learnagent/s04/
  playground/                   # S02—S04 各有工具练习目录
config.example.properties       # 根目录启动时的共享配置示例
s04/.agents/hook.json           # S04 固定从本子项目加载的命令 Hook 配置
s05/.agents/hook.json           # S05 的命令 Hook 配置，默认为空
s06/.agents/hook.json           # S06 的命令 Hook 配置，默认为空
s07/.agents/skills/             # S07 技能目录，每个技能一个 SKILL.md
s08/.agents/                    # S08 的技能目录与 Hook 配置
s09/.agents/                    # S09 的技能目录与 Hook 配置
s10/.agents/                    # S10 的技能目录与 Hook 配置
s11/.agents/                    # S11 的技能目录与 Hook 配置
playground/                     # 根目录启动时的默认练习目录
docs/lessons/                   # 各课机制和练习
docs/history/                   # 拆分前的验证记录
```

## 构建与启动

使用 JDK 17、Maven 3.9.3、Hutool、fastjson2 和 JUnit 5。自动测试无需模型密钥。从仓库根目录运行：

```powershell
# 全部课程
.\mvnw.cmd test
.\mvnw.cmd package

# 单课测试、打包：无需先 install 其他模块，也无需 -am
.\mvnw.cmd -pl s01 test
.\mvnw.cmd -pl s04 package

# 各课默认入口，无需手写 exec.mainClass
.\mvnw.cmd -pl s01 -q compile exec:java
.\mvnw.cmd -pl s02 -q compile exec:java
.\mvnw.cmd -pl s03 -q compile exec:java
.\mvnw.cmd -pl s04 -q compile exec:java
.\mvnw.cmd -pl s05 -q compile exec:java
.\mvnw.cmd -pl s06 -q compile exec:java
.\mvnw.cmd -pl s07 -q compile exec:java
.\mvnw.cmd -pl s08 -q compile exec:java
.\mvnw.cmd -pl s09 -q compile exec:java
.\mvnw.cmd -pl s10 -q compile exec:java
.\mvnw.cmd -pl s11 -q compile exec:java
```

根工程不再包含应用入口，启动时必须选课。各模块生成自己的普通 JAR，使用 `exec:java` 加载依赖运行。

也可进入子工程独立构建运行：

```powershell
cd s03
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
# 或本机 Maven：mvn test / mvn -q compile exec:java
```

独立指源码、测试和运行不需要其他课程的产物；各课仍通过 `../pom.xml` 继承父 POM，并共用根目录 Wrapper。

## 配置与工作目录

程序从**进程当前工作目录**读取 UTF-8 `config.properties`。首次运行，将该目录的 `config.example.properties` 复制为 `config.properties` 并填写：

```properties
openai.base-url=https://你的服务商地址/v1
openai.model=你的模型名称
openai.api-key=你的密钥
```

从根目录执行 `-pl s0N` 使用根配置及根 `playground/`，现有本地配置继续可用。进入子工程启动则使用子工程自己的配置和练习目录。IDE 工作目录也按同一规则设置。真实密钥不复制、不提交。

| 配置项 | 使用范围 | 默认值 |
| --- | --- | --- |
| `openai.base-url` | S01—S11，程序追加 `/chat/completions` | `https://api.openai.com/v1` |
| `openai.model` | S01—S11 | 必填 |
| `openai.api-key` | S01—S11 | 必填，仅本地文件 |
| `agent.work-dir` | S02—S11 | `playground`，相对进程工作目录 |
| `agent.max-model-calls` | S02—S11，每轮模型请求上限；S06 子代理每次 `task` 单独计数，S08 起总结请求不计入 | `10` |
| `shell.timeout-seconds` | S02—S11 | `30` |
| `shell.max-output-chars` | S02—S11 | `12000` |
| `compact.threshold-chars` | S08—S11，对话粗略字符数超过时自动总结 | `50000` |
| `compact.keep-tool-results` | S08—S11，微压缩保留原文的最近工具结果数 | `3` |
| `background.timeout-seconds` | S11，`background_run` 命令的超时 | `600` |

S01 只使用前三项，每次有效输入只请求模型一次，不声明工具、不执行命令。所有课程使用非流式 Chat Completions，模型请求超时 60 秒；输入 `exit`、`quit` 或 EOF 退出。

S02 提供 `shell`、`read_file`、`write_file`、`edit_file`、`glob`；S03 加入逐次权限确认；S04 加入 `UserPromptSubmit`、`PreToolUse`、`PostToolUse`、`Stop`。`Stop` 仅表示本轮正常最终回复，超限、失败、中断和退出不触发。S05 增加第六个工具 `todo_write`：模型提交完整任务清单，程序在内存中校验保存；连续 3 批工具调用未更新且有未完成项时，在最后一个工具结果后附加提醒。它免逐次确认，但仍经过 Hook。S06 增加第七个工具 `task`：子代理以全新消息历史独立完成子任务，只把总结交回主对话；子代理只有五个基础工具，不能再派生子代理，其工具调用仍逐次确认并经过 Hook。S07 增加第八个工具 `load_skill`：启动时扫描 `s07/.agents/skills/*/SKILL.md`，系统提示词只列技能名称和描述，模型需要时加载完整正文；它免逐次确认但经过 Hook，子代理也可加载技能。S08 增加第九个工具 `compact` 与请求前的上下文压缩：较早的长工具结果以占位说明代替；对话超过阈值或模型调用 `compact` 时，先把完整记录保存到工作目录 `.transcripts/`，再请模型总结，历史替换为系统提示词加摘要，任务清单与最新用户输入原样附加。S09 增加三个记忆工具：模型把以后仍有用的偏好、纠正和项目背景保存为工作目录 `.memory/<name>.md`，下次启动时名称与描述组成的索引进入系统提示词，正文用 `memory_read` 按需读取；读取免确认，保存和删除逐次确认，均经过 Hook，子代理没有记忆工具。S10 增加六个任务工具：`task_create` 创建任务、`task_update` 用返回的 ID 追加 `blockedBy` 依赖（拒绝自身依赖与环）、`task_claim` 在依赖全部完成后认领、`task_complete` 完成并报告解锁的下游、`task_list` / `task_get` 查看；任务保存为工作目录 `.tasks/task_xxxxxxxx.json`，跨会话保留，内容不进系统提示词。它们免逐次确认但经过 Hook，子代理没有任务工具。S11 增加两个后台工具：`background_run` 把耗时命令放到后台线程执行并立即返回 `bg_N`，模型可以继续做别的事；命令结束后，结果以 `<background-results>` 附加到下一次请求模型时的最后一条消息，不需要轮询，`background_check` 查看状态和完整输出。`background_run` 与 `shell` 共用硬拒绝和逐次确认，超时单独配置；后台任务只在本次会话中，退出时终止仍在运行的命令，子代理没有后台工具。

S04 的 Java Hook 是 Agent 内置行为，JSON Hook 是用户扩展，两者同时生效。注册顺序为内置日志、追加的 Java Hook、用户 JSON 命令。无论从哪里启动，都读取 S04 子项目下的 `s04/.agents/hook.json`，不读取父工程配置；命令通过 stdin 接收事件 JSON，前置可拦截，后置失败只告警。命令固定在 S04 目录运行，修改配置后重启。完整用法见 [S04 README](s04/README.md#json-配置-hook)。

用户命令 Hook 首次启用或配置/脚本内容变化时独立询问信任：`y` 信任并记住、`once` 仅本次、其他输入或 EOF 不启用。授权保存在用户目录 `~/.java-agent/hook-trust/`，拒绝不影响内置 Java Hook；运行中检测到内容变化会停止用户 Hook。该授权与 S03 工具审批独立，也不是命令安全审计或沙箱。

Windows Shell 使用 PowerShell，Unix 使用 `/bin/sh`。Shell 仍具有当前用户权限，工作区与文本规则不是系统沙箱。详细契约与练习见 [S01](docs/lessons/s01-agent-loop.md)、[S02](docs/lessons/s02-tool-use.md)、[S03](docs/lessons/s03-permission.md)、[S04](docs/lessons/s04-hooks.md)、[S05](docs/lessons/s05-todo-write.md)、[S06](docs/lessons/s06-subagent.md)、[S07](docs/lessons/s07-skill-loading.md)、[S08](docs/lessons/s08-context-compact.md)、[S09](docs/lessons/s09-memory.md)、[S10](docs/lessons/s10-task-system.md)、[S11](docs/lessons/s11-background-tasks.md)。

## 验证记录

拆分前 S04 已提交为 `231f8c0`。拆分后的结果见 [实战规范](docs/JAVA_PRACTICE_SPEC.md#模块拆分验证)。旧工程的真实模型及构建记录已归档至 [历史规范](docs/history/pre-modules-spec.md)，不代表新模块已重新完成真实模型验收。

2026-10-20 干净构建通过：四课共 470 项测试，467 通过、3 项因 Windows 符号链接权限跳过，无失败或错误。四个模块分别在根目录选课、子工程目录内独立打包和启动退出均通过；本次没有请求真实模型服务。

同日增加 S04 JSON 命令 Hook、与内置 Java Hook 的组合及固定子项目定位后，全量 `package` 通过：522 项测试，519 通过、3 项符号链接权限跳过。默认入口和 Python 配置示例的离线拒绝流程验证通过，未请求真实模型服务，详见 [验证记录](docs/JAVA_PRACTICE_SPEC.md#s04-json-命令-hook-验证)。

加入用户 Hook 独立信任确认后，全量 `package` 通过：545 项测试，542 通过、3 项跳过。真实入口的拒绝、单次授权、记忆授权、复用及撤销流程已在临时用户目录验证，未写入真实用户授权或请求模型，详见 [信任机制验证](docs/JAVA_PRACTICE_SPEC.md#s04-用户-hook-信任验证)。

2026-10-21 新增 S05 TodoWrite 后，全量 `package` 通过：五课共 836 项测试，832 通过、4 项符号链接权限跳过；S05 共 291 项，新增 26 项均通过。S04、S05 入口输入 exit 正常启动退出，未请求真实模型服务，详见 [S05 验证](docs/JAVA_PRACTICE_SPEC.md#s05-todowrite-验证)。

同日新增 S06 Subagent 后，全量 `package` 通过：六课共 1143 项测试，1138 通过、5 项符号链接权限跳过；S06 共 307 项，新增 16 项均通过，详见 [S06 验证](docs/JAVA_PRACTICE_SPEC.md#s06-subagent-验证)。

同日新增 S07 Skill Loading 后，全量 `package` 通过：七课共 1472 项测试，1465 通过、7 项跳过；S07 共 329 项，新增 22 项，详见 [S07 验证](docs/JAVA_PRACTICE_SPEC.md#s07-skill-loading-验证)。

同日新增 S08 Context Compact 后，全量 `package` 通过：八课共 1824 项测试，1815 通过、9 项跳过；S08 共 352 项，新增 23 项，详见 [S08 验证](docs/JAVA_PRACTICE_SPEC.md#s08-context-compact-验证)。

同日新增 S09 Memory 后，全量 `package` 通过：九课共 2196 项测试，2185 通过、11 项跳过；S09 共 372 项，新增 20 项，详见 [S09 验证](docs/JAVA_PRACTICE_SPEC.md#s09-memory-验证)。

2026-10-22 新增 S10 Task System 后，全量 `clean package` 通过：十课共 2588 项测试，2575 通过、13 项跳过；S10 共 392 项，新增 20 项，详见 [S10 验证](docs/JAVA_PRACTICE_SPEC.md#s10-task-system-验证)。

同日新增 S11 Background Tasks 后，全量 `clean package` 通过：十一课共 3003 项测试，2988 通过、15 项跳过；S11 共 415 项，新增 23 项，详见 [S11 验证](docs/JAVA_PRACTICE_SPEC.md#s11-background-tasks-验证)。
