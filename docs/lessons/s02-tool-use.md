# S02：工具与多工具

源码位于 `s02/src/main/java/dev/learnagent/s02/`，完整扩展 S01 文本循环。没有权限或 Hook 类，也不依赖 S01 的 Java 产物。

## 相较 S01 的变化

| S01 | S02 |
| --- | --- |
| ChatMessage 只有 role / content | 增加工具调用列表与结果调用 ID |
| complete(messages) 返回字符串 | complete(messages, definitions) 返回 assistant 消息 |
| 每条输入只请求模型一次 | 工具结果回传后继续请求，直到最终回复或上限 |
| 无工具执行 | AgentTool、ToolRegistry、Shell 和文件工具 |
| 三项模型配置 | 新增工具目录、请求上限、Shell 超时和输出限制 |

```text
用户输入 → 模型（五个工具 Schema）→ assistant.tool_calls
                                     ↓
                              ToolRegistry 按名称分派
                                     ↓
                               参数校验与真实执行
                                     ↓
                         tool(call_id, result) → 再请求模型
```

## 单工具到多工具

AgentTool.definition 提供名称、说明和 Schema；execute 执行参数校验和实际操作。Schema 不替代 Java 参数验证。

单工具练习可用 `new ChatLoop(client, tool, maxModelCalls)`；ToolUseMain 将五个工具注册到 ToolRegistry。同一次模型回复可含多个调用，按顺序执行并绑定各自 ID。重复注册名称在启动时拒绝，未知工具产生错误结果。

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| shell | command | PowerShell / Unix sh，独立进程 |
| read_file | path，可选正整数 limit | UTF-8 文本读取，limit 为前几行 |
| write_file | path、content | 创建父目录并覆盖写入，可为空 |
| edit_file | path、old_text、new_text | 首次精确替换；未匹配不写入 |
| glob | pattern | 排序返回相对路径，** 匹配零层或多层 |

WorkspaceFiles 检查规范化路径和真实祖先路径；glob 不进入目录链接。文件最多 1 MiB，读取最多 12000 个内容字符，glob 最多 200 条并标记截断。

Shell 默认超时 30 秒，保留前 12000 字符，每次新进程，不保留 cd 或变量。工作目录不是系统沙箱，Shell 具有当前用户权限。本课默认不询问权限。

## 循环和失败

先保存 assistant 调用，再补齐每个 tool 结果，最后请求模型。未知工具、参数错误、文件错误、非零退出码和命令超时均回传。

每轮最多请求模型 10 次，包含最终回答；超限时最后一批工具不执行，仍补齐 ID。首次模型请求失败不保留本轮；工具执行后请求失败保留历史，实际文件操作不会回滚。

## 运行和练习

```powershell
.\mvnw.cmd -pl s02 -q compile exec:java
```

输入“使用文件工具在 s02-demo/notes.txt 写入第一版，改成第二版，读取验证，再查找 txt 文件”。观察工具请求、结果和最终回答，核对调用 ID。继续追问文件名，检查历史保留。

根目录启动用根配置和 playground；进入 s02 后运行则用该目录的配置和 playground。

## 测试

```powershell
.\mvnw.cmd -pl s02 test
```

阅读顺序：ToolUseMain → AgentTool / ToolRegistry → 消息协议 → ChatLoop → 工具实现。SingleToolLoopTest 验证单工具，ChatLoopTest 验证多工具；ToolUseProtocolTest 使用本地 HTTP 服务检查五个 Schema 和回传；FileToolsTest、ShellToolTest 验证临时文件与无副作用命令。
