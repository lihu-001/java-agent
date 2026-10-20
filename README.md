# Java Agent 实战

当前实现 s01 Agent Loop 和 s02 Tool Use：通过 OpenAI 兼容接口对话，程序执行模型请求的工具并回传结果，再请求模型，直到给出最终回答。支持一次回复中的多个工具调用，按返回顺序执行。输入 `exit`、`quit` 或遇到输入结束时退出。

使用 JDK 17、Maven、Hutool 和 fastjson2。s01 只提供 `shell`；s02 新增文件读写、编辑、查找和按名称分派工具的注册表。

## 构建与启动

在项目根目录编辑 `config.properties`（UTF-8 编码），填写服务商地址、模型和密钥。新检出项目时，先复制 `config.example.properties` 为 `config.properties`。

```properties
openai.base-url=https://你的服务商地址/v1
openai.model=你的模型名称
openai.api-key=你的密钥
```

值不要加引号。保存后在项目根目录运行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package

.\mvnw.cmd -q compile exec:java

# 第二课（引号保证 PowerShell 正确传递 Maven 属性）
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s02.ToolUseMain"
```

也可直接使用已安装的 Maven，例如 `mvn test`、`mvn package`、`mvn -q compile exec:java`。Wrapper 固定 Maven 3.9.3，首次运行需联网下载 Maven 和依赖。`package` 生成普通 JAR，运行时通过 `exec:java` 加载依赖。

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `openai.base-url` | 服务商的 API 基础地址，保留其要求的路径前缀；程序追加 `/chat/completions` | 省略时使用 `https://api.openai.com/v1` |
| `openai.model` | 服务商支持的模型 ID | 必填 |
| `openai.api-key` | API 密钥，仅在本地设置 | 必填 |
| `agent.work-dir` | Shell 及 S02 文件工具的工作目录；相对路径以程序启动目录为基准，不存在时自动创建 | `playground` |
| `agent.max-model-calls` | 每次用户输入最多请求模型的次数，包含最终回答请求 | `10` |
| `shell.timeout-seconds` | 每条 Shell 命令的超时秒数 | `30` |
| `shell.max-output-chars` | 每条命令保留的输出字符数（不含状态和截断提示） | `12000` |

基础地址不要填写完整的 `/chat/completions` 地址。程序启动时读取当前工作目录的 `config.properties`，修改后需重启；在 IDE 中运行时将工作目录设为项目根目录。API 配置只从文件读取。`config.properties` 已加入 Git 忽略规则，不打入 JAR，示例文件只保留空值。

后三项限制必须为正整数。已有的 `config.properties` 无需修改，会采用默认值。模型需支持 Chat Completions 的 `tools` / `tool_calls` 协议。

请求采用非流式 Chat Completions 协议，连接和单次请求超时均为 60 秒。模型请求失败时不会自动重试：尚未执行工具的本轮不保存；已经记录的工具调用和结果会保留，实际操作不会回滚。输入下一条消息即可继续。历史保存在内存，重启清空，当前未做截断或摘要。

达到调用上限时，最后一批工具不再执行，但每个调用仍补齐 `tool` 结果和终止原因，避免下一轮携带不完整的协议历史。工具参数错误、未知工具、非零退出码和命令超时会回传模型，模型可以在上限内继续处理。

## 观察工具循环

启动后输入：

```text
列出当前练习目录的文件，读取 example.txt，并说明它的用途。
```

终端依次显示 `工具请求>`、`工具结果>` 和 `助手>`。默认练习目录是项目下的 `playground`。可以继续问“刚才读取了哪个文件？”观察历史保留。

Windows 使用 `powershell.exe -NoProfile -NonInteractive`，Unix 使用 `/bin/sh`。每次调用启动独立进程，`cd` 和变量不会跨调用保留。标准输出和错误输出合并，超过长度限制后继续排空管道，仅保留前面的字符。超时或中断时尽力终止仍可枚举到的进程树；第一课不支持后台任务管理。PowerShell 输出设置为 UTF-8，外部程序也需要输出 UTF-8 才能正确解码。

`shell` 会真实执行模型提出的命令，具有当前用户的权限。工作目录不是系统沙箱，命令仍可访问其他路径；许可机制属于后续 s03。

## 从哪里读代码

- `src/main/java/dev/learnagent/s01/AgentLoopMain.java`：加载配置，组装客户端和终端输入输出。
- `src/main/java/dev/learnagent/s01/AgentConfig.java`：以 UTF-8 读取 `config.properties`，校验必填项。
- `src/main/java/dev/learnagent/s01/ChatLoop.java`：本课重点，负责循环、历史和退出。
- `src/main/java/dev/learnagent/s01/ModelClient.java`：模型调用入口，便于离线验证循环。
- `src/main/java/dev/learnagent/s01/OpenAiClient.java`：构造 HTTP 请求，解析文本和 `tool_calls`，序列化 `tool_call_id`。
- `src/main/java/dev/learnagent/s01/ChatMessage.java`：普通消息、模型工具调用消息和工具结果消息。
- `src/main/java/dev/learnagent/s01/ToolCall.java`、`ToolDefinition.java`：模型请求执行的调用与程序提供的工具说明。
- `src/main/java/dev/learnagent/s01/AgentTool.java`、`ShellTool.java`：工具执行接口及有超时、输出限制的 Shell 实现。
- `src/test/java/dev/learnagent/s01/`：循环、本地 HTTP 协议和真实 Shell 测试，不调用真实模型。

阅读[第一课讲解](docs/lessons/s01-agent-loop.md)了解工具循环及消息顺序；后续规划见[实战方案](docs/JAVA_PRACTICE_SPEC.md)。

学习顺序为：对话循环 → 对话加工具 → 回传工具结果并多次请求模型。当前代码包含三个步骤的完整能力；`agent.max-model-calls` 限制每条用户消息触发的模型请求次数，不是一次回复中的工具调用数量。

## 第二课：Tool Use

入口为 `dev.learnagent.s02.ToolUseMain`。沿用根目录 `config.properties`，无需新增配置项。`shell.timeout-seconds` 和 `shell.max-output-chars` 仍仅控制 Shell。

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `shell` | `command` | 复用 S01 的 Shell 执行 |
| `read_file` | `path`，可选正整数 `limit` | 读取 UTF-8 文本，`limit` 表示前几行 |
| `write_file` | `path`、`content` | 创建父目录并写入文件，覆盖已有内容，可写空文件 |
| `edit_file` | `path`、`old_text`、`new_text` | 只替换首次精确匹配；找不到或 `old_text` 为空时不修改 |
| `glob` | `pattern` | 按路径模式查找；`**/*.txt` 包含根目录和各级子目录 |

文件工具检查规范化路径和已有祖先的真实路径，拒绝越出工作目录；`glob` 不递归进入目录链接或 Windows junction。读取和编辑的源文件、写入后的内容最多 1 MiB；读取结果最多 12000 个内容字符；查找按相对路径排序，最多返回 200 条，并标注截断。路径模式使用 `/` 分隔，支持 `*`、`?`、独立路径段 `**` 以及 JDK 单路径段 glob 语法。

这些检查不是系统沙箱，不防御其他进程并发替换路径或硬链接；Shell 仍具有当前用户权限。许可规则留到 S03。

启动 S02 后可输入：

```text
在 s02-demo/notes.txt 写入“第一版”，把“第一版”改为“第二版”，读取文件验证，再查找 s02-demo 下的 txt 文件。请使用专用文件工具。
```

阅读顺序：`s02/ToolUseMain.java` 组装五个工具 → `s01/ToolRegistry.java` 注册和分派 → `s02` 的四个 `*Tool.java` 及 `WorkspaceFiles.java` → 原有 `s01/ChatLoop.java` 观察工具结果回传。文件工具位于 `src/main/java/dev/learnagent/s02/`，测试位于对应测试包。

S02 在 S01 上增量开发，共用原有 `ChatLoop`、配置、模型客户端、消息协议和 Shell。原循环通过 `ToolRegistry` 从单工具扩展到多工具，S01 入口只注册 Shell，S02 入口再注册四个文件工具；两课共用一份循环实现。详见[第二课讲解与练习](docs/lessons/s02-tool-use.md)。

## 验证边界

2026-10-20 使用 JDK 17.0.2 和 Maven Wrapper 3.9.3 完成 `package`，55 项自动测试全部通过，普通 JAR 构建成功。自动测试使用模拟模型、本地 HTTP 服务和临时目录中的真实 Shell，验证协议和程序行为。

本地配置的真实模型已通过“列出目录、读取中文练习文件 → 工具结果回传 → 最终回答 → 历史追问 → 退出”的验收。Windows PowerShell 已在本机验证；Unix 分支尚未在本机运行。详见 [S01 收尾验证记录](docs/JAVA_PRACTICE_SPEC.md#s01-收尾验证记录)。

2026-10-20 S02 完成后再次执行 `package`：共 102 项测试，101 项通过、1 项跳过，无失败或错误。跳过项需要 Windows 符号链接权限；Windows junction 越界检查已实际通过。真实模型完成“写入中文文件 → 精确编辑 → 读回 → glob 查找 → 历史追问 → 退出”。S01 原启动命令也已验证。详见 [S02 验证记录](docs/JAVA_PRACTICE_SPEC.md#s02-验证记录)。
