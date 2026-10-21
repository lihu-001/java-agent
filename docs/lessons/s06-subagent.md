# S06：Subagent 子代理

本课在独立 s06 子工程中，以 S05 完整源码为起点增加 `task` 工具。入口为 `dev.learnagent.s06.SubagentMain`，保留五个基础工具、`todo_write`、S03 权限与 S04 Hook。

## 1. 为什么需要子代理

S05 之前只有一份消息历史。让模型“找出项目里所有用到 X 的地方”，它会连续 glob、read_file 十几次，每个工具结果都留在历史里。任务完成后，这些中间内容仍占用上下文，后续每次请求都要重新发送，也会干扰模型对后续问题的判断。

子代理把这类子任务放进一份独立的临时历史：

```text
主代理历史：system → user → assistant(task) → tool(子代理总结) → assistant(最终回复)
                                     │                 ▲
                                     ▼                 │
子代理历史（临时）：system → user(prompt) → assistant → tool → … → assistant(总结)
```

主代理只看到最后的总结，中间的十几次读取不会进入主历史。代价是子代理看不到主对话，主代理必须在 `prompt` 中把任务交代清楚。

## 2. 协议

```text
task({"description":"查找用法","prompt":"在工作目录中找出所有 .txt 文件，读取后总结各自的用途，返回文件路径列表和一句话说明。"})
```

- `description`：3—10 字的简称，只用于终端日志，最多 40 字符。
- `prompt`：交给子代理的完整说明，最多 8000 字符。
- 结果：`子代理结果（模型请求 N 次，工具调用 M 个）：` 加子代理最终回复；超过 12000 字符截断。
- 达到上限：子代理每次 `task` 最多请求模型 `agent.max-model-calls` 次，最后一批工具不执行，返回“子代理未完成”加最后的说明。
- 失败：子代理模型请求超时或失败时，返回 `工具错误：...`，主代理可以决定重试或换方法；已执行的操作不回滚。

## 3. 实现要点

`SubagentRunner.run` 是一个精简的循环，和 `ChatLoop` 的区别：

| | ChatLoop（主） | SubagentRunner（子） |
| --- | --- | --- |
| 输入来源 | 终端逐行读取 | `prompt` 参数，一次性 |
| 历史 | 会话内跨轮保留 | 方法内局部变量，返回即丢弃 |
| UserPromptSubmit / Stop | 触发 | 不触发（不是用户输入，也不是本轮最终回复） |
| PreToolUse / PostToolUse | 触发 | 触发（同一个 ToolRegistry Hook 链） |
| 清单提醒 | 有 | 无 |

最关键的一行是新建历史，而不是复制主历史：

```java
List<ChatMessage> messages = new ArrayList<>();
messages.add(new ChatMessage("system", systemPrompt));
messages.add(new ChatMessage("user", prompt));
```

`SubagentMain.createLoop` 组装两套工具注册表，共用同一个 `PermissionGate` 和 `AgentHooks`：

```java
ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks);          // 五个基础工具
SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(), SUBAGENT_PROMPT, output);
ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks,
        List.of(new TodoWriteTool(todos), new TaskTool(runner)));               // 主代理七个工具
```

子代理工具里没有 `task`，从结构上禁止递归派生；没有 `todo_write`，子代理不会改写主代理的清单。

## 4. 与权限、Hook、清单的关系

| 机制 | 行为 |
| --- | --- |
| S03 权限 | `task` 本身直接允许；子代理的写入、编辑、Shell 仍逐次向用户确认，拒绝后子代理拿到“权限拒绝”结果 |
| S04 PreToolUse | `task` 触发一次，可拦截（拦截后子代理不启动）；子代理每个工具调用再各触发一次 |
| S04 PostToolUse | 顺序为 `pre:task → pre:子工具 → post:子工具 → post:task`，`post:task` 的结果是子代理总结 |
| S04 Stop | 只在主代理本轮最终回复时触发 |
| S05 清单提醒 | `task` 算主代理的一批工具；子代理内部的批次不计数 |

用户 JSON Hook 的 matcher 可以写 `task`，单独拦截或记录子代理派发。

## 5. 练习

1. 启动后输入：“派一个子代理查看 playground 下有哪些文件、各自内容是什么，然后告诉我总结。”观察终端中缩进的子代理日志，以及主代理收到的只有一段总结。
2. 输入：“让子代理在 playground 写一个 hello.txt。”子代理的写入仍需确认，输入 n 后观察子代理如何汇报、主代理如何转述。
3. 在 `s06/.agents/hook.json` 中为 `PreToolUse` 配置 `matcher: "task"` 且 `exit 2` 的命令，观察主代理在禁止派发时是否改为自己调用工具。
4. 把 `agent.max-model-calls` 改为 2，让子代理执行需要多步的任务，观察“子代理未完成”结果。

## 6. 验证

自动测试覆盖：子代理从全新历史开始且每次独立、最终只返回文字、达到上限时不执行最后一批、空回复和请求失败；`task` 参数校验、结果截断、失败转工具错误；主代理只看到总结而看不到子代理读到的内容、子代理工具列表不含 `task`/`todo_write`、子代理写入仍需确认、Hook 两层触发顺序、Hook 拦截 `task` 时子代理不启动、子代理不能改动主清单。全部使用模拟模型与临时目录，不需要 API 密钥。模型是否在合适时机主动派发、`prompt` 是否写清楚，需要真实服务单独验收。
