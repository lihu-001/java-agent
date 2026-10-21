# S05：TodoWrite

复制 S04 完整能力，新增 `todo_write` 工具：模型为多步骤任务维护一份会话内任务清单，每推进一步就提交最新完整清单。程序在模型连续多批工具调用都没更新清单时，自动附加提醒。S03 权限、S04 Hook 与用户 JSON Hook 信任机制保持不变。

## 包结构

根包只保留默认入口 `TodoMain`，S04 的 `HooksMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s05
├── TodoMain                  # 默认入口，组装完整 S05
├── app                       # ToolUseMain / PermissionMain / HooksMain 前几课组装入口
├── config                    # AgentConfig、S05ProjectDirectory
├── loop                      # ChatLoop：新增可选的 TodoReminder
├── model / tool / tool.file  # 与 S04 相同
├── permission                # todo_write 直接放行
├── hook                      # 与 S04 相同，配置固定为 s05/.agents/hook.json
└── todo                      # 本课新增：清单、工具和提醒
```

学习顺序：[todo](src/main/java/dev/learnagent/s05/todo) → [TodoMain](src/main/java/dev/learnagent/s05/TodoMain.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s05/loop/ChatLoop.java) 中带 `S05` 注释的提醒附加点。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `todo.TodoStatus / TodoItem` | 新增：`pending`、`in_progress`、`completed` 三种状态与单项数据 |
| `todo.TodoList` | 新增：会话内存清单，整体替换；最多 20 项、单项 200 字符、最多 1 项进行中，失败不改状态 |
| `todo.TodoWriteTool` | 新增：`todo_write` 工具，校验参数，返回渲染后的清单 |
| `todo.TodoReminder` | 新增：统计连续未更新的工具批次，默认 3 批后提醒，每轮输入清零 |
| `loop.ChatLoop` | 新增带 `TodoReminder` 的构造方法；提醒附加到本批最后一个工具结果，不新增消息角色 |
| `permission.PermissionPolicy` | `todo_write` 直接允许；其他工具规则不变 |
| `app.ToolUseMain.createTools` | 新增追加工具的重载，五个基础工具不变 |
| `app.HooksMain` | 由根包移入 `app`；抽出 `createHooks` 供 `TodoMain` 复用同一注册顺序 |
| `config.S05ProjectDirectory` | 由 S04 版本改名，定位本子项目 |
| `TodoMain` | 默认入口：六个工具、共享 Hook 链、提醒，系统提示词增加清单用法 |

生产源码位于 [src/main/java/dev/learnagent/s05](src/main/java/dev/learnagent/s05)，测试位于 [src/test/java/dev/learnagent/s05](src/test/java/dev/learnagent/s05)，S04 的全部回归测试保留，`todo` 子包为新增测试。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s05 -q compile exec:java`，使用根目录模型配置与 `playground/`。用户 Hook 固定读取本模块 [`.agents/hook.json`](.agents/hook.json)，默认为空；启用方式与信任确认同 [S04](../s04/README.md#json-配置-hook)，信任记录按项目路径区分，S04 的授权不会带到 S05。

## 清单与提醒

示例：模型收到“把 playground 下所有 txt 合并成 all.txt 并统计行数”，会先调用：

```json
{"todos":[{"content":"查找 txt 文件","status":"in_progress"},
          {"content":"读取并合并","status":"pending"},
          {"content":"写入 all.txt 并统计行数","status":"pending"}]}
```

工具结果把清单交还模型：

```text
已更新。任务清单（0/3 完成）：
[>] 查找 txt 文件
[ ] 读取并合并
[ ] 写入 all.txt 并统计行数
```

清单只在本次会话内存中，跨轮保留，退出即丢失，不写文件。每次调用都整体替换；校验失败返回工具错误，原清单不变。`todo_write` 不需要逐次确认，但仍经过 PreToolUse/PostToolUse Hook，Hook 可以拦截它。

本轮中若清单为空或有未完成项，且模型连续 3 批工具调用都没有调用 `todo_write`，程序在第 3 批最后一个工具结果后附加 `<reminder>…</reminder>` 并在终端显示 `提醒>`。全部完成后不再提醒；达到模型调用上限的那一批不附加。提醒只是提示，模型仍可忽略。

详细讲解见 [课程文档](../docs/lessons/s05-todo-write.md)。
