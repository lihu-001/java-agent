# 用 Java 实战 Learn Claude Code

状态：按逐步实战推进；已选择 OpenAI 兼容接口。已实现 s01 对话循环、s02 文件工具与分派、s03 权限规则和逐次确认、s04 提示词记录前、工具调用前后和任务完成后四类钩子，验证记录见文末。

## 目标与现状

通过逐课实现，掌握模型调用、工具执行、上下文管理和任务协作，最终构建可运行的命令行编程 Agent。每课采用“解释机制 → Java 实现 → 观察调用过程 → 动手练习 → 验证结果”的顺序。

本地已确认：Windows PowerShell、JDK 17.0.2、Maven 3.9.3。项目已有 Maven 配置、完整 s01 源码和离线测试。2026-10-20 验证进程从系统级 `JAVA_HOME` 加载 JDK 17；用户级仍指向 JDK 8，需注意环境变量冲突。

参考 [上游仓库](https://github.com/shareAI-lab/learn-claude-code)。调查时 main 提交为 `ce8f9f186058939da54c9d6fead78dfb5d0fd6c3`。课程以根目录 `s01_*` 至 `s17_*` 为准；`agents/` 和 `docs/` 是旧版 12 章体系，章节编号不能混用。详见[上游版本说明](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/README-zh.md#版本说明)。

## 技术选择

- Java 17：复用现有环境，使用普通类、record 和 JDK 标准库。
- Maven Wrapper：在项目中固定构建工具版本，通过 `mvnw.cmd` 下载并调用 Maven。
- JDK `HttpClient`：显式构造模型请求，方便观察消息及工具协议。
- Hutool：优先使用其工具类；fastjson2：处理 JSON；JUnit 5：验证循环和消息顺序。依赖版本固定在 `pom.xml`。
- 命令行入口：让每课的循环和工具调用直接可见。
- 模型接口：OpenAI 兼容 Chat Completions，非流式文本及 `tools` / `tool_calls`；模拟客户端用于离线测试。

参考：[Maven Wrapper](https://maven.apache.org/tools/wrapper/)、[JDK 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)、[ProcessBuilder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html)。

## 课程范围

以下为完整课程的规划；当前实现至 s04，四课基于同一循环增量扩展，其余内容待逐步推进。S04 已完成自动验证，真实模型验收尚未执行。

| 课次 | 主题 | Java 实战产出 |
| --- | --- | --- |
| s01 | Agent Loop | 能调用 Shell 并继续对话的最小循环 |
| s02 | Tool Use | 文件读写、编辑、查找和工具分派 |
| s03 | Permission | 操作规则与许可决策 |
| s04 | Hooks | 提示词记录前、工具调用前后、任务完成后的四类扩展接口 |
| s05 | TodoWrite | 可更新的步骤清单 |
| s06 | Subagent | 拥有独立消息历史的子任务执行器 |
| s07 | Skill Loading | 技能目录与按需加载 |
| s08 | Context Compact | 上下文预算、裁剪和摘要 |
| s09 | Memory | 跨会话知识保存与召回 |
| s10 | Task System | 带依赖关系的持久化任务 |
| s11 | Background Tasks | 后台执行与完成通知 |
| s12 | Cron Scheduler | 持久化调度与时间触发 |
| s13 | Agent Teams | 队友通信、任务认领及工作目录隔离 |
| s14 | MCP Plugin | 外部工具发现和接入 |
| s15 | Integrated Harness | 集成前述机制的运行时 |
| s16 | Workflow Runtime | 固定工作流与执行记录恢复 |
| s17 | Goal Loop | 完成判断、续跑与退出条件 |

课程名称对应[上游目录](https://github.com/shareAI-lab/learn-claude-code/tree/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3)。按课次推进，每课验证后再进入下一课；s15 集成，s16 和 s17 单独展示新增机制。

## 拟建项目结构

```text
pom.xml
mvnw / mvnw.cmd / .mvn/wrapper/
src/main/java/dev/learnagent/s01/   # 第一课入口与循环
src/test/java/dev/learnagent/s01/   # 第一课行为测试
src/main/java/dev/learnagent/s02/   # 第二课入口、工具分派和文件工具
src/test/java/dev/learnagent/s02/   # 文件工具、循环和 HTTP 集成测试
src/main/java/dev/learnagent/s03/   # 权限规则、确认器和第三课入口
src/test/java/dev/learnagent/s03/   # 权限策略、确认及循环集成测试
src/main/java/dev/learnagent/s04/   # 四类 Hook 接口、分派器、日志示例与入口
src/test/java/dev/learnagent/s04/   # 钩子顺序、异常与循环集成测试
docs/lessons/s01-agent-loop.md     # 原理、运行步骤与练习
docs/lessons/s02-tool-use.md       # 第二课讲解、工具约定与练习
docs/lessons/s03-permission.md     # 第三课原理、权限规则与练习
docs/lessons/s04-hooks.md          # 第四课钩子原理、约定与练习
playground/                       # 练习工作目录
```

后续课程使用 `s02`、`s03` 等包放新增能力，并在已有实现上增量扩展，不复制完整循环。S02 已直接复用并扩展 S01 的 `ChatLoop`，通过注册表注入工具。只有出现实际复用需求时再提取公共组件，保留早期课程的可读性和独立运行方式。

## 第一课范围与验收

### 当前实施范围

S01 按用户确认的三个步骤推进：先对话循环，再对话加工具并展示结果，最后才是自动回传工具结果并多次请求模型。当前源码已经实现第三步；后续课程应逐步讲解、实现和验收，避免提前加入下一步能力。

当前流程为：读取输入 → 携带历史和工具定义请求模型 → 保存模型消息 → 执行工具并回传结果 → 再请求模型 → 无工具调用时结束本轮。支持空输入跳过、`exit` / `quit` / EOF 退出，以及 60 秒模型请求超时。不自动重试失败的模型请求。

已实现工具定义、Shell 执行、按调用 ID 回传结果、多次工具循环、未知工具及无效参数反馈、命令超时和输出截断。每轮默认最多请求模型 10 次，命令默认超时 30 秒，保留前 12000 个输出字符；均可通过配置调整。达到模型调用上限时，最后一批工具不执行，补齐结果并停止。本轮首次模型请求失败时不保存；工具调用和结果已记录后发生模型请求失败时保留历史，因为操作不会回滚。讲解见 [s01-agent-loop.md](lessons/s01-agent-loop.md)。

### 完整第一课目标与验收

对应[上游第一课源码](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/s01_agent_loop/code.py)。

循环顺序：接收用户输入 → 请求模型 → 保存模型消息 → 检查工具调用 → 执行工具 → 按调用 ID 回传结果 → 再请求模型。没有工具调用时输出回答并结束本轮。

第一课验收条件：

1. 用户输入“列出当前练习目录的文件并说明各文件用途”，能观察到真实工具请求、执行结果和最终回答。
2. 无工具调用时直接返回；一次回复包含多个工具调用时，每个结果都有正确的调用 ID。
3. 工具失败、未知工具及超时能产生明确结果；模型请求失败时向终端报告错误。
4. 设置最大循环次数、命令超时和输出长度限制；超过限制时明确说明终止原因。
5. 对话历史在连续提问时保留；退出命令能正常结束程序。
6. 离线测试验证消息协议；真实 API 验证单独记录，不能用模拟通过代替。

原版 Windows 使用 `cmd.exe` 执行名为 `bash` 的工具。当前 Java 版在 Windows 使用 PowerShell，在 Unix 使用 `/bin/sh`，统一命名为 `shell`，并在工具描述中明确语法。通过 `ProcessBuilder` 设置练习目录、读取输出并处理退出码。每次调用使用新进程。工作目录限制本身不构成系统沙箱，进程树清理是尽力而为，尚未引入后台任务管理。

## 当前命令

以下命令用于构建、测试和运行已实现的课程：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd -q compile exec:java
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s02.ToolUseMain"
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s03.PermissionMain"
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s04.HooksMain"
```

运行前填写项目根目录的 `config.properties`，包含 `openai.base-url`、`openai.model` 和 `openai.api-key`，具体说明见根目录 README。配置以 UTF-8 读取，不使用 API 环境变量。本地配置文件已加入 Git 忽略规则；密钥不出现在示例、日志或提交中。

## 代码风格与测试策略

文件使用 UTF-8、Java 使用四空格缩进。类名用 PascalCase，方法及变量用 camelCase。优先使用直观的数据类型，例如：

```java
record ToolResult(String callId, String output, boolean error) {}
```

当前使用 JUnit 5 和模拟模型响应测试多轮历史、空输入、退出、错误恢复、中断、多工具消息顺序和调用上限；HTTP 测试使用本地模拟服务检查工具协议、响应解析和超时；Shell 测试使用临时目录验证中文输出、退出码、参数错误、输出截断和超时。测试类命名为 `*Test.java`。暂不设覆盖率百分比，以关键行为能验证为准。

## 实施边界与待定项

始终：保留已完成课程的运行方式；记录与上游的差异；运行所改行为的测试；真实调用与模拟结果分别说明。

已确认：逐步实现，先讲原理再基于已有代码实现，OpenAI 兼容接口，当前推进至第四课四类生命周期钩子。后续如要变更 Java 主版本或改为 Web 应用，再讨论范围。

禁止：提交密钥、将未运行的命令标记为验证通过、将简单命令过滤描述成可靠沙箱。

当前边界：本地所配置服务商的基本工具循环已验证，不代表所有服务商兼容；Unix Shell 分支尚未在本机运行。

## S01 收尾验证记录

日期：2026-10-20。环境：Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。

### 自动测试与构建

验证终端未继承新的环境变量，因此仅在本次进程中设置：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
.\mvnw.cmd package
```

上述设置依赖本机系统级 `JAVA_HOME` 已指向 JDK 17；其他机器应使用实际的 JDK 17 安装目录。未修改持久环境变量。

最终构建结果：`BUILD SUCCESS`。55 项测试全部通过，失败、错误和跳过均为 0：

| 测试类 | 测试数量 |
| --- | --- |
| `AgentConfigTest` | 14 |
| `ChatLoopTest` | 9 |
| `OpenAiClientTest` | 19 |
| `ShellToolTest` | 13 |

生成 `target/java-agent-1.0-SNAPSHOT.jar`，为普通 JAR，启动仍使用 `exec:java` 加载依赖。

### 真实模型验收

使用已有的本地 `config.properties`，运行 `.\mvnw.cmd -q compile exec:java`。密钥和服务商配置不写入验收记录。

1. 要求模型仅通过 `shell` 执行 `Get-ChildItem -Name` 和 `Get-Content -Encoding UTF8 example.txt`。
2. 模型返回一条 `shell` 调用，在同一个命令中执行目录列出和文件读取。工具结果的调用 ID 与请求一致，退出码为 0，成功读取中文练习内容。
3. 工具结果回传后，模型说明 `example.txt` 用于第一课练习：列出目录、读取文件并依据真实结果回答。
4. 继续问“刚才读取了哪个文件”，模型依据历史正确回答 `example.txt`，没有再次调用工具。
5. 输入 `exit`，程序正常退出，进程退出码为 0。

首次联调发现 PowerShell 模块初始化的进度 XML 混入输出；在 Shell 启动脚本中设置 `$ProgressPreference = 'SilentlyContinue'` 后，重新通过全部测试、打包及上述真实模型流程，最终工具输出不再夹带该 XML。

此次真实验收覆盖基本工具闭环和连续对话；多工具调用 ID、错误路径、超时及调用上限由离线测试覆盖。Windows 范围内 S01 收尾完成，未进入 S02。

## S02 验证记录

日期：2026-10-20。环境：Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。

已实现 `dev.learnagent.s02.ToolUseMain` 入口，新增 `read_file`、`write_file`、`edit_file`、`glob`。在原有 `s01.ChatLoop` 上增加工具注册表和提示词参数，以 `s01.ToolRegistry` 替换单工具分派；S01 与 S02 共用这一份循环、配置、协议、HTTP 客户端和 Shell。循环仍按“保存 assistant → 顺序执行工具 → 按 ID 补齐 tool → 再请求模型”推进。S01 的单工具构造方式和默认入口保留。讲解见 [s02-tool-use.md](lessons/s02-tool-use.md)。

### 自动测试与构建

共用原有循环后，仅在验证进程中选择系统级 JDK 17，然后执行干净构建，避免旧的重复类残留：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
.\mvnw.cmd clean package
```

结果为 `BUILD SUCCESS`，生成普通 JAR。共 102 项测试，101 项通过，1 项跳过，失败和错误均为 0。

| 测试范围 | 数量 | 结果 |
| --- | --- | --- |
| S01 原有四个测试类 | 55 | 全部通过 |
| S02 `ChatLoopTest` | 7 | 全部通过 |
| S02 `FileToolsTest` | 36 | 35 通过，1 跳过 |
| 共用 `s01.ToolRegistryTest` | 3 | 全部通过 |
| S02 `ToolUseProtocolTest` | 1 | 通过 |

文件测试覆盖中文及空内容、覆盖写入、首次精确替换、换行保留、无匹配不修改、严格参数校验、目录及缺失文件、大小限制、非法 UTF-8、相对与绝对越界路径、glob 的零层及多层匹配、排序与截断。

符号链接测试因本机权限不足而跳过，不能视为验证通过。单独创建 Windows 目录 junction 验证了读取、编辑、经已有链接新建文件的越界拒绝及 glob 排除，测试通过并清理了链接。

循环测试覆盖不同工具同批执行的顺序与调用 ID、错误修正、文件写入后的 HTTP 失败及历史保留、调用上限避免最后一批写入、退出与中断。本地 HTTP 集成测试使用真实 `OpenAiClient`，验证五个工具 Schema 和写入→编辑→读取→查找结果的协议回传，未请求外部 API。

### 真实模型验收

以下为文件工具实现后的真实模型验收记录；循环合并后重新通过上述离线及本地 HTTP 测试，并验证两个入口启动与退出，没有重复请求真实服务商。真实验收使用已有 `config.properties`，未修改模型配置或记录密钥，运行：

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s02.ToolUseMain"
```

1. 要求仅用四个文件工具操作新建的 `s02-check-69ec73b4/notes.txt`，不调用 Shell 或访问其他文件。
2. `write_file` 写入“第一版”，返回 9 个 UTF-8 字节；`edit_file` 将其替换为“第二版”。
3. `read_file` 返回“第二版”；`glob` 使用 `s02-check-69ec73b4/**/*.txt` 找到该文件，验证 `**` 匹配零层子目录。
4. 模型依据工具结果总结成功；继续追问后，未再调用工具，正确回答文件路径和最终内容。
5. 输入 `exit` 正常结束，进程退出码为 0。验收临时文件读回确认后清理。

另外以 `exit` 输入运行原 S01 启动命令，确认仍显示第一课工具并正常退出。

本次真实验收只代表本地配置服务商的基本文件工具闭环成功；错误恢复与多工具同批协议由离线测试验证。Unix 分支及需要权限的符号链接测试尚未在本机验证。当前范围截至 S02，未实现 S03 许可机制。

## S03 验证记录

日期：2026-10-20。环境：Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。

先介绍“执行前决策、硬拒绝优先、按次确认、拒绝结果回传”的机制，再在 S02 上增量实现。新增 `ToolPermission` 接口和 `s03` 包中的 `PermissionPolicy`、`PermissionGate`、`PermissionMain`；现有 `ToolRegistry` 增加可选检查器，`ToolUseMain.createTools` 增加权限参数，`WorkspaceFiles.resolve` 开放给策略复用。本课没有修改或复制 `ChatLoop`，没有复制 Shell 和文件工具。

默认规则：工作区内读取与查找直接执行，写入、编辑以及未命中硬拒绝的 Shell 调用逐次询问；越界文件路径和示例硬拒绝规则直接拒绝。确认只接受 `y` / `yes`，其他输入或 EOF 拒绝，不保存跨调用授权。没有新增配置项。规则与上游差异见 [s03-permission.md](lessons/s03-permission.md)。

### 自动测试与构建

仅在当前验证进程选择系统级 JDK 17：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
.\mvnw.cmd package
```

结果为 `BUILD SUCCESS`，共 154 项测试：153 项通过、1 项跳过，失败和错误均为 0。唯一跳过项仍是 S02 的 Windows 符号链接权限测试。

| 范围 | 数量 | 结果 |
| --- | --- | --- |
| S01 / S02 原有测试 | 102 | 101 通过，1 跳过 |
| 注册表权限检查新增测试 | 2 | 全部通过 |
| `PermissionPolicyTest` | 30 | 全部通过 |
| `PermissionGateTest` | 15 | 全部通过 |
| `PermissionLoopTest` | 5 | 全部通过 |

覆盖允许、拒绝、询问、硬拒绝优先级、无效参数、越界路径、未知规则默认拒绝、确认答案大小写、空输入、EOF、输入失败、检查器异常与中断。循环集成验证混合调用的全部 ID、拒绝不写文件、授权不延续到下一次编辑、确认输入不进入聊天历史、调用上限不询问不执行，以及已批准的编辑和无副作用 Shell 命令。

危险命令只作为权限策略的待分类字符串；实际 Shell 执行测试使用 `echo permission-ok`。S01 / S02 现有本地 HTTP 模拟协议测试也通过。

### 真实模型验收

使用已有本地 `config.properties`，未修改配置或记录密钥，运行：

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s03.PermissionMain"
```

验收输入由测试脚本按顺序提供，包括明确的单次 `n` 和 `y` 答案，限定只操作新建目录 `s03-check-67d75abf` 内的两个文件，不调用 Shell：

1. 模型请求 `write_file` 创建 `denied.txt`，程序显示调用 ID、完整参数和权限询问。输入 `n` 后，工具结果返回“权限拒绝、工具未执行”，模型回答“未执行”。
2. 下一轮明确请求创建 `allowed.txt`，内容“已批准的中文内容”。程序再次询问，输入 `y` 后写入成功，返回 24 个 UTF-8 字节。
3. 下一轮请求 `read_file`，无需确认即读回准确内容，模型据实回答。
4. 输入 `exit`，程序正常结束，进程退出码为 0。
5. 直接检查文件系统，确认 `denied.txt` 不存在、`allowed.txt` 内容完全一致，然后清理本次临时文件及空目录。

分别对 S01、S02 原启动命令提供 `exit`，两个入口均正常启动并结束。用户已有的学习笔记和 `playground/example.txt` 改动未修改。

真实验收覆盖拒绝、单次批准及无确认读取；其他规则与协议边界由自动测试覆盖。Shell 权限规则是文本匹配而非沙箱；Unix 分支及受本机权限限制的符号链接测试未在本机验证。当前实现截至 S03，未进入 S04。

## S04 验证记录

日期：2026-10-20。环境：Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。

按用户指定范围实现四类事件：用户提示词记录前 `UserPromptSubmit`、工具调用前 `PreToolUse`、工具调用后 `PostToolUse`、任务完成后 `Stop`。Hook 相关类全部位于 `s04`：`AgentHook` 定义接口，`AgentHooks` 统一分派，`LoggingHook` 展示四类日志，`HooksMain.createLoop` 接收自定义 Java 回调列表。

在原 `s01.ChatLoop` 增加提示词记录前和正常最终回复后的触发点，在 `s01.ToolRegistry` 增加工具前后触发点，共用同一条事件链。复用 S02 五个工具和 S03 权限规则，没有复制对话循环。旧构造方法使用空事件链，S01—S03 入口继续可用。

默认只注册日志示例，移除先前的 `ProtectedPathHook`；`protected/` 不再具有额外规则。前置返回非 null 或 IO / 运行时异常时拒绝本次事件；后置失败保留原结果，继续其他回调。提示词被拒绝时不记录、不请求模型。完成事件仅在本轮正常最终回复保存并输出后触发，不将超限、失败、超时、中断或退出当成任务完成。消息以不可变快照提供给回调。

本课支持代码注册的同步 Java Hook，不包含配置文件加载、外部脚本、异步调度或停止回调强制续跑。实现及示例见 [s04-hooks.md](lessons/s04-hooks.md)。

### 自动测试与构建

仅在当前验证进程选择系统级 JDK 17，没有修改持久环境变量：

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
.\mvnw.cmd clean test "-Dtest=dev.learnagent.s04.*Test"
.\mvnw.cmd package
```

先清理旧编译产物，避免移除的类残留。S04 共 36 项测试全部通过。全量 `BUILD SUCCESS`，生成普通 JAR；共 190 项测试，189 项通过、1 项因 Windows 符号链接权限跳过，失败和错误均为 0。

| 范围 | 数量 | 结果 |
| --- | --- | --- |
| S01—S03 原有测试 | 154 | 153 通过，1 跳过 |
| `HookRegistryTest` | 16 | 全部通过 |
| `HooksLoopTest` | 4 | 全部通过 |
| `LifecycleHooksTest` | 16 | 全部通过 |

验证四事件顺序及自定义回调一次注册、提示词记录前的历史快照、每轮完成快照隔离、前置拒绝不污染历史、空输入及退出不触发、权限确认不视作新提交、正常最终回复仅完成一次。另覆盖请求失败/超时、工具执行后的请求失败、调用超限、中断均不误报完成；后置异常保留结果和历史、继续后续回调；警告接收器故障不破坏事件链。

工具集成仍使用模拟模型及临时目录内真实文件工具，覆盖混合调用 ID、拒绝无写入、逐次确认、权限不可绕过和后置失败后的结果回传。原有本地 HTTP 协议和 Shell 测试随全量构建通过。

### 入口验证与真实模型边界

分别向 S01—S04 的 `exec:java` 入口提供 `exit`，四个入口均正常启动并退出，进程退出码为 0。S04 展示四类事件和原有五个工具，退出不触发 `Stop`。

本次没有请求外部模型 API，入口验证不能代替真实模型验收。S04 手动练习和自定义 Hook 注册示例已写入课程文档；Unix 分支及需要权限的符号链接测试未在本机验证。当前实现截至 S04，未进入 S05。
