# 用 Java 实战 Learn Claude Code

状态：按逐步实战推进；已选择 OpenAI 兼容接口。本次只实现 s01 的纯文本对话循环，工具调用后续再实现。

## 目标与现状

通过逐课实现，掌握模型调用、工具执行、上下文管理和任务协作，最终构建可运行的命令行编程 Agent。每课采用“解释机制 → Java 实现 → 观察调用过程 → 动手练习 → 验证结果”的顺序。

本地已确认：Windows PowerShell、JDK 17.0.2、Maven 3.9.3，`mvn` 已在 PATH 中。项目已有 Maven 配置、第一步源码和离线测试。

参考 [上游仓库](https://github.com/shareAI-lab/learn-claude-code)。调查时 main 提交为 `ce8f9f186058939da54c9d6fead78dfb5d0fd6c3`。课程以根目录 `s01_*` 至 `s17_*` 为准；`agents/` 和 `docs/` 是旧版 12 章体系，章节编号不能混用。详见[上游版本说明](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/README-zh.md#版本说明)。

## 技术选择

- Java 17：复用现有环境，使用普通类、record 和 JDK 标准库。
- Maven Wrapper：在项目中固定构建工具版本，通过 `mvnw.cmd` 下载并调用 Maven。
- JDK `HttpClient`：显式构造模型请求，方便观察消息及工具协议。
- Hutool：优先使用其工具类；fastjson2：处理 JSON；JUnit 5：验证循环和消息顺序。依赖版本固定在 `pom.xml`。
- 命令行入口：让每课的循环和工具调用直接可见。
- 模型接口：OpenAI 兼容 Chat Completions，非流式文本回答；模拟客户端用于离线测试。

参考：[Maven Wrapper](https://maven.apache.org/tools/wrapper/)、[JDK 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)、[ProcessBuilder](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/ProcessBuilder.html)。

## 课程范围

以下为完整课程的规划；当前仅实现 s01 的纯文本多轮对话，其余内容待逐步推进。

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

本次范围由用户明确为“先只实现循环，暂不实现工具调用”：读取输入 → 携带历史请求模型一次 → 输出文本 → 保存成功问答 → 等待下一次输入。支持空输入跳过、`exit` / `quit` / EOF 退出、请求失败提示和 60 秒超时。失败轮次不保存，不自动重试。

当前验收关注：多轮消息顺序、退出、错误恢复、HTTP 请求和中文响应解析。工具定义、Shell、工具结果回传及自动工具循环均未实现。第一步讲解见 [s01-agent-loop.md](lessons/s01-agent-loop.md)。

### 后续完整第一课目标

对应[上游第一课源码](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/s01_agent_loop/code.py)。

循环顺序：接收用户输入 → 请求模型 → 保存模型消息 → 检查工具调用 → 执行工具 → 按调用 ID 回传结果 → 再请求模型。没有工具调用时输出回答并结束本轮。

第一课验收条件：

1. 用户输入“列出当前练习目录的文件并说明各文件用途”，能观察到真实工具请求、执行结果和最终回答。
2. 无工具调用时直接返回；一次回复包含多个工具调用时，每个结果都有正确的调用 ID。
3. 工具失败、未知工具及超时能产生明确结果；模型请求失败时向终端报告错误。
4. 设置最大循环次数、命令超时和输出长度限制；超过限制时明确说明终止原因。
5. 对话历史在连续提问时保留；退出命令能正常结束程序。
6. 离线测试验证消息协议；真实 API 验证单独记录，不能用模拟通过代替。

原版 Windows 使用 `cmd.exe` 执行名为 `bash` 的工具。拟议 Java 版在 Windows 使用 PowerShell，在 Unix 使用 `/bin/sh`，统一命名为 `shell`，并在工具描述中明确语法。通过 `ProcessBuilder` 设置练习目录、读取输出并处理退出码。工作目录限制本身不构成系统沙箱。

## 当前命令

以下命令用于构建、测试和运行当前纯文本版本：

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

当前使用 JUnit 5 和模拟模型响应测试多轮历史、空输入、退出、错误恢复和中断；HTTP 测试使用本地模拟服务检查请求、响应解析和超时。后续实现工具时再增加工具和 Shell 测试。测试类命名为 `*Test.java`。暂不设覆盖率百分比，以关键行为能验证为准。

## 实施边界与待定项

始终：保留已完成课程的运行方式；记录与上游的差异；运行所改行为的测试；真实调用与模拟结果分别说明。

已确认：逐步实现，OpenAI 兼容接口，本次仅对话循环。后续如要变更 Java 主版本或改为 Web 应用，再讨论范围。

禁止：提交密钥、将未运行的命令标记为验证通过、将简单命令过滤描述成可靠沙箱。

当前待定：真实服务商的 Base URL 与模型名称，可在本地配置后验证，无须在聊天中提供密钥。真实 API 尚未验证。
