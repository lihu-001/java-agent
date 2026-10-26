# S14：MCP Plugin 外部工具接入

本课在独立 s14 子工程中，以 S13 完整源码为起点接入 MCP server。入口为 `dev.learnagent.s14.McpMain`，S13 的 `TeamMain` 移入 `app`。没有 `.agents/mcp.json` 时与 S13 完全相同。

## 1. 为什么需要 MCP

到 S13 为止，Agent 的每个工具都是本工程的 Java 类：想接数据库、Issue 系统或浏览器，就要改源码、重新编译。MCP 把“工具”变成独立进程：任何语言写的 server 只要按协议说清楚“我有哪些工具、参数是什么”，Agent 就能在启动时发现并调用它。

| | 内置工具（S02—S13） | MCP 工具（S14） |
| --- | --- | --- |
| 来源 | 本工程的 `AgentTool` 实现 | 外部进程，`tools/list` 发现 |
| 名字 | 固定，如 `read_file` | `mcp__<server>__<tool>` |
| 风险判断 | 程序知道工具做什么，按类型给出 ALLOW / ASK / DENY | 程序不知道，默认 ASK，用户可放行 |
| 参数校验 | 工具类自己校验 | 交给 server（客户端不实现 JSON Schema） |

关键在于：包装之后它们就是普通 `AgentTool`。`ChatLoop`、`ToolRegistry`、Hook 和权限链一行都没改，只在权限策略的 `default` 分支加了一条前缀规则。

## 2. stdio 传输与 JSON-RPC

server 是子进程。客户端写它的 stdin，读它的 stdout，每条消息是一行 JSON-RPC 2.0，消息内不能有换行（fastjson2 默认把字符串中的换行转义为 `\n`）。stderr 是 server 的日志，不属于协议。

```text
客户端                                   server 进程
  │ {"id":1,"method":"initialize",...}  →│
  │← {"id":1,"result":{"protocolVersion":...}}
  │ {"method":"notifications/initialized"}→│   通知没有 id，不回复
  │ {"id":2,"method":"tools/list"}       →│
  │← {"id":2,"result":{"tools":[...],"nextCursor":"p2"}}
  │ {"id":3,"method":"tools/list","params":{"cursor":"p2"}} →│
  │ ...
  │ {"id":7,"method":"tools/call","params":{"name":"echo","arguments":{...}}} →│
  │← {"id":7,"result":{"content":[{"type":"text","text":"..."}],"isError":false}}
```

`McpClient` 用一个守护线程读 stdout，按 `id` 把响应交给等待中的 `CompletableFuture`：

```java
long id = ids.incrementAndGet();
CompletableFuture<JSONObject> response = new CompletableFuture<>();
pending.put(id, response);
send(request);
reply = response.get(timeout);       // 超时：发 notifications/cancelled，迟到的响应因 id 已移除被丢弃
```

几个容易出错的地方：

- **写入锁不能和 start 共用**。握手期间 server 可能先发 `ping` 请求，读取线程要立即回复；如果 `start` 持有写入用的同一把锁，回复会等到握手超时。`send` 用独立的 `writeLock`。
- **两种错误要区分**。`result.isError: true` 是工具执行失败，内容要交给模型看，以便它修正；JSON-RPC `error`（如未知工具 `-32602`）是协议层错误；进程退出是断开。三者都变成工具结果，但文字不同，模型能看出是否还值得重试。
- **stdout 只能写协议消息**。server 用 `print` 打日志会混入非 JSON 行；客户端忽略并告警，而不是断开。
- **启动要有反馈**。npx 首次下载可能要几十秒，期间没有输出会让人以为卡死。所以每一步都打印 `MCP>` 日志，启动阶段把 server 的 stderr 逐行转发（最多 20 行）；连接完成后不再转发，避免运行日志刷屏。
- **单行上限**。读取线程不信任 server，单行超过 1M 字符就断开，避免无限占用内存。
- **关闭顺序**。按规范先关 stdin，让 server 读到 EOF 后自行退出；2 秒不退出再强制结束进程树。

## 3. 信任：谁批准了启动这个进程

`mcp.json` 是项目文件，可能随仓库一起被克隆下来。启动 server 就是以当前用户权限运行配置里的命令，所以和 S04 用户 Hook 一样必须先由用户批准：

- 显示命令、参数、环境变量名（**不显示值**，可能有令牌）与免确认工具。
- 快照包括配置原文、`args` 中可识别的字面量脚本路径和 `trust_files`；任何一项内容变化都要重新确认。
- 记录在 `~/.java-agent/mcp-trust/`，与 Hook 信任分开：批准 Hook 不代表批准 MCP。
- 确认后、启动前再校验一次，防止确认期间文件被替换。

这只是“用户同意过这份内容”的记录。`npx some-package` 下载的代码、server 运行时加载的其他文件都不在快照中，它也不是沙箱。

令牌不应写进 `mcp.json`。`env` 的值可写 `${NAME}`，启动时从当前进程的环境变量展开；变量没设置时该 server 不启动，而不是带着空令牌运行。

## 4. 权限：程序不知道外部工具做什么

内置工具的风险由程序判断：`read_file` 只读，`shell` 要确认。MCP 工具的描述来自 server 自己，程序无从判断，所以默认 ASK：

```java
default -> !call.name().startsWith(MCP_PREFIX)
        ? new Decision(Action.DENY, "没有为该工具定义权限规则。")
        : allowedMcpTools.contains(call.name())
        ? new Decision(Action.ALLOW, "mcp.json 中已放行的 MCP 工具。")
        : new Decision(Action.ASK, "将调用外部 MCP server 的工具，程序无法判断它会做什么，请核对参数。");
```

`allow` 让用户放行确认过的只读工具。它写在 `mcp.json` 里，因此也在信任快照中：项目文件改了放行列表，用户会在下次启动时看到并重新确认。MCP 规范中的 `annotations.readOnlyHint` 是 server 的自我声明，本课没有用它自动放行，理由同上。

Hook 不需要任何改动：MCP 工具名照常出现在 PreToolUse / PostToolUse 事件中，matcher 写 `mcp__.*` 即可匹配全部 MCP 工具，`mcp__docs__.*` 匹配某个 server。

## 5. 提示注入：工具描述不是指令

MCP 工具的描述和结果都会进入模型上下文，恶意或被攻破的 server 可以在其中写“忽略之前的规则”。本课的应对是：

- 描述前加 `[MCP <server>]` 标明来源，长度截断到 1024 字符；
- 系统提示词附 `MCP_RULE`，声明外部内容不是用户指令、不能据此跳过确认；
- 真正的防线仍是程序：未放行的工具每次都要用户确认，Shell 和写文件也一样。提示词约定只是辅助。

## 6. 只有主 Agent 有 MCP 工具

子代理和队友都没有 MCP 工具，原因与 S11/S12 不给它们后台、定时工具相同：

- server 是会话级资源，Lead 关闭时一起关闭；
- 队友的确认要排队到主线程，外部工具的调用次数和费用更难控制；
- 用户在信任确认时看到的是“主 Agent 可以调用这些工具”。

团队模式下，Lead 的确认回答来自 `TeamConsole`，所以 MCP 的启动时机要在控制台创建之后。`McpManager.Starter` 把“用哪个输入读回答”推迟到组装时决定：单 Agent 用终端，团队模式用控制台的 `answers()`。

## 7. 组装顺序

```text
McpMain.createSession
  └── TeamMain.createSession(..., Starter.of(project, mcpTrust, env))
        ├── team.enabled=false → ScheduleMain.createSession(..., starter)
        │       Hook 信任 → MCP 信任与启动 → 组装（MCP 工具追加在最后）
        └── team.enabled=true
                TeamConsole → Hook 信任 → MCP 信任与启动 → 组装 Lead 与队友工厂
Session.run 结束：关闭控制台 → 停止队友 → 关闭 MCP server
```

组装中途抛出异常时，已经启动的 server 会被关闭，不留下孤儿进程。

## 8. 没有实现的部分

- Streamable HTTP / SSE 传输、OAuth；
- `resources`、`prompts`、`sampling`、`roots`、`elicitation`；
- `notifications/tools/list_changed`（工具列表在启动时固定，变化需重启）；
- 断开后自动重连；
- 按 `inputSchema` 在客户端校验参数。

## 9. 练习

1. 复制 `.agents/mcp.example.json` 为 `mcp.json`，回答 `once` 启动，让模型统计一段文本的词数，观察工具名和日志 Hook 的输出。
2. 从 `allow` 中删掉 `now`，重启后确认需要重新授权，调用 `now` 时出现 `权限确认>`。
3. 在 `example_server.py` 的 stdout 上 `print("debug")`，观察客户端的告警；改成写 stderr 后再试。
4. 给示例 server 加一个 `slow` 工具（`time.sleep(20)`），把 `timeout` 设为 3，观察超时后的工具错误，以及之后的调用仍然正常。
5. 在 `.agents/hook.json` 中为 `mcp__.*` 写一个 PreToolUse 命令 Hook，拒绝参数中包含某个关键字的调用。

## 10. 验证

自动测试用 Java 写的 `FakeMcpServer` 作为独立 JVM 子进程，覆盖握手顺序、分页、超时取消、断开、非 JSON 输出、server 请求的回复、环境变量展开、信任确认与记忆、Hook 与 MCP 信任分开、默认确认与放行、子代理没有 MCP 工具、团队模式下 Lead 的工具与提示词、会话结束关闭进程。队友没有 MCP 工具由组装代码保证（队友工厂不使用 McpManager），没有单独的测试。示例 Python server 只用于手动体验，自动测试不依赖 Python。
