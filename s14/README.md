# S14：MCP Plugin 外部工具接入

复制 S13 的全部能力，新增 **MCP（Model Context Protocol）客户端**。启动时读取本子项目的 `.agents/mcp.json`，经用户单独确认后，以子进程启动每个 stdio server，完成 `initialize` 握手，用 `tools/list` 发现工具，并包装成 `mcp__<server>__<tool>` 交给主 Agent。模型调用这些工具和调用内置工具一样，都经过 S04 Hook 和 S03 权限检查。S05—S13 的全部能力保持不变。

**没有 `mcp.json` 时（默认），S14 与 S13 完全相同**：工具、系统提示词和权限都不变，也不会弹出确认。

## 包结构

根包只保留默认入口 `McpMain`，S13 的 `TeamMain` 移入 `app` 作为上一课入口：

```text
dev.learnagent.s14
├── McpMain                   # 默认入口：创建 MCP 启动器，交给 TeamMain 组装
├── app                       # ToolUseMain … ScheduleMain / TeamMain（前几课入口）
├── permission                # mcp__ 前缀的工具默认 ASK；mcp.json allow 中的全名 ALLOW
├── loop / model / tool / hook / todo / subagent / skill / compact / memory / task / background / schedule / team   # 与 S13 相同
└── mcp                       # 本课新增
    ├── McpConfig             # 读取并校验 .agents/mcp.json（mcpServers，仅 stdio）
    ├── McpTrustSnapshot      # 被批准的内容：配置原文 + args 中的字面量脚本 + trust_files
    ├── McpTrustStore         # 用户目录 .java-agent/mcp-trust/，与 Hook 信任分开
    ├── McpClient             # 一个 server 进程：逐行 JSON-RPC、握手、分页列工具、调用、超时取消、关闭
    ├── McpTool               # 把一个 MCP 工具包装成 AgentTool，结果只取文本
    └── McpManager            # 确认、逐个启动、汇总工具与放行列表、提示词规则、关闭全部
```

学习顺序：[mcp/McpConfig](src/main/java/dev/learnagent/s14/mcp/McpConfig.java) → [mcp/McpClient](src/main/java/dev/learnagent/s14/mcp/McpClient.java) → [mcp/McpTool](src/main/java/dev/learnagent/s14/mcp/McpTool.java) → [mcp/McpManager](src/main/java/dev/learnagent/s14/mcp/McpManager.java) → [permission/PermissionPolicy](src/main/java/dev/learnagent/s14/permission/PermissionPolicy.java) 的 `default` 分支 → [McpMain](src/main/java/dev/learnagent/s14/McpMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `mcp.*` | 新增，见上 |
| `permission.PermissionPolicy` | 新增带放行集合的构造方法；`mcp__` 开头的工具在放行集合中则 ALLOW，否则 ASK；其他未知工具仍 DENY |
| `app.ScheduleMain` | `createSession` 新增 `McpManager.Starter` 重载：Hook 确认之后启动 MCP，工具追加在最后，提示词附 MCP 规则；`Session` 增加 `mcp` |
| `app.TeamMain` | 由根包移入 `app`；同样新增 Starter 重载，团队模式下 MCP 确认也经过主线程控制台；`Session.run` 结束时关闭 MCP server |
| `McpMain` | 默认入口；`McpManager.Starter.of(项目目录, 信任存储, 环境变量)` 交给 `TeamMain` |

`ChatLoop` 与 `ToolRegistry` 不修改：MCP 工具就是普通 `AgentTool`。

## 配置

`.agents/mcp.json`，格式接近 Claude Code 的 `mcpServers`，但只支持 stdio：

```json
{
  "mcpServers": {
    "example": {
      "command": "python",
      "args": ["examples/mcp/example_server.py"],
      "env": { "API_TOKEN": "${MY_API_TOKEN}" },
      "timeout": 10,
      "allow": ["word_count", "now"],
      "trust_files": ["examples/mcp/helpers.py"]
    }
  }
}
```

| 字段 | 说明 |
| --- | --- |
| 名称 | 小写字母开头，只含小写字母、数字、`-`，最长 32；不含 `_`，`mcp__<server>__<tool>` 才能无歧义拆分；最多 8 个 |
| `command` / `args` | 可执行文件和参数列表，直接交给 `ProcessBuilder`，**不经过 Shell**；进程工作目录为 S14 子项目 |
| `env` | 追加的环境变量；值可写 `${NAME}` 引用当前进程的环境变量，令牌不必写进文件；变量未设置则该 server 不启动 |
| `timeout` | 握手、列工具和每次调用的超时，1—300 秒，默认 30 |
| `allow` | 免确认的工具名（server 内的名字）；其余工具每次调用都确认 |
| `trust_files` | 额外纳入信任校验的文件，内容变化后需要重新确认 |

`type`、`url` 等字段会被拒绝：本课不实现 HTTP 传输。配置格式错误阻止启动；单个 server 启动、握手或列工具失败只告警并跳过，stderr 末尾一段会显示出来便于排查。

## 运行时行为

- **信任确认**：与 S04 用户 Hook 相同的流程，但存储分开（`~/.java-agent/mcp-trust/`）。首次或内容变化时显示每个 server 的命令、参数、环境变量名（不显示值）和免确认工具，回答 `y` 记住、`once` 仅本次、其他或 EOF 不启动。确认之后、启动之前再校验一次内容。
- **协议**：每条 JSON-RPC 消息一行。`initialize`（协议版本 `2025-06-18`）→ `notifications/initialized` → `tools/list`（按 `nextCursor` 翻页，每个 server 最多 64 个工具）。调用超时发送 `notifications/cancelled`，连接继续可用。server 发来的 `ping` 回复空结果，其他请求（sampling、roots）回复 `-32601`。stdout 上的非 JSON 行忽略并告警；启动期间 stderr 逐行显示（最多 20 行，如 npx 下载进度和启动报错），之后只保留末尾 2000 字符用于诊断。
- **结果**：`content` 中的文本按顺序拼接，图片等非文本内容替换为占位说明，没有文本时用 `structuredContent`；`isError: true` 以 `工具错误：` 开头回传，超过 12000 字符截断。JSON-RPC 错误和断开都转为工具错误。
- **权限与 Hook**：MCP 工具名进入 PreToolUse / PostToolUse，可以用 matcher `mcp__.*` 匹配。默认逐次确认；`allow` 只放行 server 确实提供的工具，列了但不存在的名字会告警。
- **启动日志**：有 `mcp.json` 时依次显示读取的配置、`[i/n] 正在启动`、进程 pid、握手结果（server 名称、版本、协议、耗时）、获取工具列表、已连接或失败原因，最后一行汇总连接数、失败数与免确认工具数；会话结束时显示已关闭的 server。没有 `mcp.json` 时不输出任何内容。
- **范围**：只有主 Agent（团队模式下为 Lead）有 MCP 工具；子代理和队友都没有，它们的权限策略也不放行任何 MCP 工具。
- **生命周期**：server 在会话开始时启动，`exit`、EOF 或异常结束时关闭：先关 stdin 等待 2 秒，再强制结束进程树。断开后不自动重连，调用返回“server 已断开”。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s14 -q compile exec:java`。体验示例 server（需要 Python 3）：

```powershell
Copy-Item .agents\mcp.example.json .agents\mcp.json
..\mvnw.cmd -q compile exec:java
```

启动时回答 `once`，看到 `MCP> example 已连接，2 个工具：word_count、now` 后，让模型“用 MCP 工具统计这句话的词数”。`word_count` 与 `now` 在 `allow` 中，不再确认；从 `allow` 中删掉一个后重启（内容变化需要重新确认），再调用就会出现 `权限确认>`。

协议细节和设计取舍见 [课程文档](../docs/lessons/s14-mcp-plugin.md)。
