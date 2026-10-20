# S02：Tool Use

## 1. 机制：循环不变，工具可以增加

S01 的模型只认识 `shell`。S02 增加 `read_file`、`write_file`、`edit_file`、`glob`，让模型直接表达文件操作，不必先生成对应操作系统的 Shell 命令。

每个工具仍由两部分组成：

- `definition()`：名称、用途、JSON Schema。模型通过这些信息选择工具并生成参数。
- `execute(arguments)`：Java 实际执行的逻辑。Schema 是说明，程序仍必须校验参数。

`ToolRegistry` 把名称映射到 `AgentTool`。工具定义和执行器来自同一份注册列表，避免声明了工具却漏掉执行分支；重复名称在启动时拒绝。

```text
用户输入 → 模型（看到五个工具定义）→ assistant.tool_calls
                                     ↓
                              ToolRegistry 按名称分派
                                     ↓
                         执行参数校验、文件操作或 Shell
                                     ↓
                          tool(call_id, result) → 模型
```

一次模型回复可以要求多个不同工具；程序按返回顺序执行。例如先写入、再编辑、最后读取，同一批后面的工具能看到前面工具的结果。每个工具结果绑定自己的调用 ID，全部补齐后再请求模型。

## 2. Java 实现

| 文件 | 职责 |
| --- | --- |
| `ToolUseMain` | 复用配置和 HTTP 客户端，组装五个工具 |
| `s01.ToolRegistry` | 从原循环的单工具分派扩展而来，生成工具定义列表、按名称分派、回传错误 |
| `ToolArguments` | 使用 fastjson2 解析对象，校验字段及类型，不强制转换数字或字符串 |
| `WorkspaceFiles` | 工作目录边界检查、UTF-8 严格解码、文件大小限制 |
| `ReadFileTool` | 读取文本，可限制前几行，标注输出截断 |
| `WriteFileTool` | 创建父目录，创建或覆盖文件 |
| `EditFileTool` | 首次精确文本替换；匹配失败时不写文件 |
| `GlobTool` | 按路径段匹配、遍历和排序，限制返回条数 |
| `s01.ChatLoop` | 原有循环增加注册表和提示词参数，S01 与 S02 共用 |

S02 在 S01 上增量开发，直接复用 `s01` 包中的 `ChatLoop`、`AgentConfig`、`ModelClient`、`OpenAiClient`、`ChatMessage`、`ToolCall`、`ToolDefinition`、`AgentTool` 和 `ShellTool`，没有第二份循环实现。

原有 `ChatLoop(client, tool, maxModelCalls)` 构造方式继续保留，内部把单个工具放进注册表。新增的构造方式接受 `ToolRegistry` 和系统提示词，S02 入口只负责注册五个工具并传入文件工具使用说明。历史管理、模型请求、调用上限、结果回传和错误恢复都由原循环负责。

错误也会成为 `tool` 消息：参数缺失、错误类型、未知工具、文件不存在、未找到替换文本、越界路径都可以交给模型修正。HTTP 失败不自动重试；文件已写入后发生 HTTP 失败时，保留工具历史，文件不会回滚。

## 3. 启动与观察

先按 README 填写 `config.properties`，使用 JDK 17：

```powershell
.\mvnw.cmd test
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s02.ToolUseMain"
```

`agent.work-dir` 默认是 `playground`，文件工具和 Shell 使用同一目录。其余配置也沿用 S01；S02 没有新增配置项。原命令 `.\mvnw.cmd -q compile exec:java` 仍启动 S01。

输入以下任务：

```text
请使用专用文件工具，在 s02-demo/notes.txt 写入“第一版”，把它改成“第二版”，读取验证，再查找 s02-demo 下的 txt 文件。根据实际结果总结。
```

观察终端中的 `工具请求>`、`工具结果>` 和 `助手>`。期望出现写入、编辑、读取、查找；模型可能分成多次请求，也可能把有明确参数的操作放在同一次回复中。

再问“刚才修改了哪个文件，最终内容是什么？”观察历史是否被保留。最后输入 `exit`。

## 4. 工具约定与边界

- 所有文本读写使用 UTF-8。读取或编辑非 UTF-8 内容时报错；编辑保留未替换部分的换行符。
- `write_file` 会覆盖已有内容，`content` 可以为空。返回实际 UTF-8 字节数。
- `edit_file` 区分大小写、精确匹配，只替换第一次；`old_text` 不能是空字符串，可以是空白；`new_text` 为空表示删除匹配部分。
- `read_file.limit` 必须是正整数，表示最多显示前几行。源文件超过 1 MiB 仍会拒绝，不因 `limit` 很小而读取大文件。输出最多保留 12000 个内容字符，截断提示不计入。
- 源文件读取、编辑和写入后的内容上限均为 1 MiB。大小检查失败时不写文件；普通磁盘写入失败不保证原子回滚。
- `glob` 使用 `/` 分隔路径。`*.txt` 匹配当前层，`**/*.txt` 包括当前层及任意子目录，`a/**/b.txt` 包括 `a/b.txt`。单段模式由 JDK glob 匹配器处理；排序使用相对路径文本，最多显示 200 条。限制的是结果数量，仍需遍历工作目录。
- 文件路径允许工作区内的绝对路径或相对路径。检查规范化路径以及最近已有祖先的真实路径，因此新建文件也不能经已有目录链接写到工作区外。`glob` 不递归进入目录符号链接或 junction，排除指向外部的匹配项。

文件路径检查不是系统沙箱，不防御硬链接或其他进程并发替换路径。Shell 仍以当前用户权限执行。权限规则与许可决策属于 S03，本课没有加入审批流程。

## 5. 动手练习

1. 创建一个包含两个“第一版”的文件，再调用一次 `edit_file`，读取验证只有第一个被替换。
2. 读取不存在的文件，观察模型能否根据错误改用 `glob` 查找。
3. 用 `read_file` 的 `limit=1` 读取多行文件，观察剩余行提示。
4. 请求文件工具读取 `../README.md`，观察越界结果；不要要求模型改用 Shell 绕过检查。
5. 将 `agent.max-model-calls` 临时设为 `1`，请求写文件，观察最后一批工具未执行；然后恢复配置。

## 6. 与上游的关系及验收

对应项目固定提交的 [s02_tool_use/code.py](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/s02_tool_use/code.py)，使用根目录的 17 章体系。

一致之处：五个工具、注册分派、文件工作区检查、首次精确替换、glob 最多 200 条，循环形状不变。

Java 版差异：使用 OpenAI 兼容 Chat Completions；工具名为 `shell`，Windows 使用 PowerShell；继承模型调用上限和 Shell 限制；增加严格参数类型、UTF-8 解码及文件体积限制；glob 不跟随目录链接，使用 JDK 单段模式语法。工具错误按 OpenAI 协议通过 `tool_call_id` 回传。

离线验收覆盖文件操作及错误、路径逃逸、匹配与截断、多工具顺序、调用 ID、错误后恢复、历史及调用上限。HTTP 集成测试使用本地模拟服务，不需要 API 密钥。真实服务商验证与自动测试分别记录在[实战方案](../JAVA_PRACTICE_SPEC.md)中。
