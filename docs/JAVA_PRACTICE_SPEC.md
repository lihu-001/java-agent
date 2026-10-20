# 用 Java 实战 Learn Claude Code

状态：按逐步实战推进；已选择 OpenAI 兼容接口。s01 的对话与 Shell 工具调用循环已完成 Windows 本机测试、打包和真实模型验收，尚未进入 s02。

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

以下为完整课程的规划；当前实现 s01 的多轮对话和 Shell 工具循环，其余内容待逐步推进。

| 课次 | 主题 | Java 实战产出 |
| --- | --- | --- |
| s01 | Agent Loop | 能调用 Shell 并继续对话的最小循环 |
| s02 | Tool Use | 文件读写、编辑、查找和工具分派 |
| s03 | Permission | 操作规则与许可决策 |
| s04 | Hooks | 工具执行前后的扩展接口 |
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
docs/lessons/s01-agent-loop.md     # 原理、运行步骤与练习
playground/                       # 练习工作目录
```

后续课程使用 `s02`、`s03` 等独立包。只有出现实际复用需求时再提取公共组件，保留早期课程的可读性和独立运行方式。

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

以下命令用于构建、测试和运行当前第一课：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
.\mvnw.cmd -q compile exec:java
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

已确认：逐步实现，OpenAI 兼容接口，本次完成第一课工具调用。后续如要变更 Java 主版本或改为 Web 应用，再讨论范围。

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
