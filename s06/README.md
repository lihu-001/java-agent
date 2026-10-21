# S06：Subagent

复制 S05 完整能力，新增 `task` 工具：主代理把探索性子任务派发给子代理。子代理从全新的消息历史开始，独立完成多次模型请求和工具调用，最后只把一段总结作为 `task` 的工具结果交回主对话。S03 权限、S04 Hook 与信任机制、S05 任务清单保持不变。

## 包结构

根包只保留默认入口 `SubagentMain`，S05 的 `TodoMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s06
├── SubagentMain              # 默认入口，组装完整 S06
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain 前几课组装入口
├── config                    # AgentConfig、S06ProjectDirectory
├── loop / model / tool       # 与 S05 相同
├── permission                # task 直接放行，子代理的工具调用另行检查
├── hook / todo               # 与 S05 相同，Hook 配置固定为 s06/.agents/hook.json
└── subagent                  # 本课新增：SubagentRunner、TaskTool
```

学习顺序：[subagent/SubagentRunner](src/main/java/dev/learnagent/s06/subagent/SubagentRunner.java) → [subagent/TaskTool](src/main/java/dev/learnagent/s06/subagent/TaskTool.java) → [SubagentMain](src/main/java/dev/learnagent/s06/SubagentMain.java) 中的两套工具注册表。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `subagent.SubagentRunner` | 新增：子代理循环。每次运行新建历史（子代理系统提示词 + 任务），执行工具直到最终回复或达到上限，历史随即丢弃 |
| `subagent.TaskTool` | 新增：`task` 工具，参数 `description`（≤40 字符）和 `prompt`（≤8000 字符）；结果为子代理总结，超过 12000 字符截断；模型请求失败转为工具错误 |
| `permission.PermissionPolicy` | `task` 直接允许；子代理内部的写入、编辑、Shell 仍逐次确认 |
| `app.TodoMain` | 由根包移入 `app`，作为 S05 入口保留 |
| `config.S06ProjectDirectory` | 由 S05 版本改名，定位本子项目 |
| `SubagentMain` | 默认入口：主代理七个工具、子代理五个基础工具，共用权限与 Hook 链 |

`loop.ChatLoop` 未修改：子代理不复用主循环，因为主循环负责读用户输入、Stop Hook 和清单提醒，子代理都不需要。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s06 -q compile exec:java`。用户 Hook 固定读取本模块 [`.agents/hook.json`](.agents/hook.json)，默认为空；信任记录按项目路径区分，S05 的授权不会带到 S06。

## 主代理与子代理

| | 主代理 | 子代理 |
| --- | --- | --- |
| 消息历史 | 整个会话跨轮保留 | 每次 `task` 新建，结束即丢弃 |
| 看到的内容 | 用户输入、自己的工具结果、子代理总结 | 只有子代理系统提示词和 `prompt` |
| 工具 | 五个基础工具 + `todo_write` + `task` | 五个基础工具 |
| 权限、Hook | S03、S04 | 同一权限确认与 Hook 链 |
| 调用上限 | `agent.max-model-calls` 每轮 | 同一配置值，每次 `task` 单独计数 |

子代理没有 `task`，所以不能递归派生；没有 `todo_write`，所以不会改动主代理的清单。终端中子代理的过程以 `  子代理[简称]` 缩进显示，但这些过程不进入主代理历史。

详细讲解见 [课程文档](../docs/lessons/s06-subagent.md)。
