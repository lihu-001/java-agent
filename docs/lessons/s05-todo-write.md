# S05：TodoWrite 任务清单

本课在独立 s05 子工程中，以 S04 完整源码为起点增加 `todo_write`。入口为 `dev.learnagent.s05.TodoMain`，保留五个基础工具、S03 权限与 S04 Hook。

## 1. 为什么需要任务清单

S04 的循环可以连续调用多个工具，但计划只存在于模型某次回复的文字中。任务步骤多了以后，早期计划被工具结果淹没，模型容易遗漏步骤，或者在完成一半时就给出最终回复。

TodoWrite 把计划变成一个工具：模型主动提交结构化清单，程序校验后保存并把渲染结果作为工具结果交回。这样每次更新都会在历史中留下最新完整清单，模型和用户都能看到进度。程序只保存状态，不决定步骤内容，也不验证某步是否真的完成。

## 2. 协议

```text
todo_write({"todos":[{"content":"...","status":"pending|in_progress|completed"}, ...]})
```

- 整体替换：每次提交完整清单，而不是增删单项。实现简单，也避免模型与程序对编号的理解不一致。空数组表示清空。
- 校验：最多 20 项；内容去除首尾空白后非空、最多 200 字符；同一时间最多 1 项 `in_progress`，促使模型一次专注一步；不允许未定义字段和未知状态。
- 失败：返回 `工具错误：...` 交给模型修正，原清单保持不变。
- 作用域：仅在本次会话内存中，跨轮保留，退出即丢失。持久化的任务系统属于后续 S10。

## 3. 提醒机制

模型可能列出清单后忘记更新。`TodoReminder` 在每批工具执行后检查清单版本号：

```text
有效用户输入 → startTurn() 清零
  每批工具执行后 → afterToolBatch()
    ├─ 本批更新过清单 → 计数清零
    └─ 未更新 → 计数 +1
         └─ 达到 3 且清单为空或有未完成项 → 返回提醒，计数清零
```

提醒附加在本批最后一个工具结果的末尾，用空行分隔：

```text
<reminder>已连续多批工具调用未更新任务清单。多步骤任务请用 todo_write 提交最新完整清单并标记进度。</reminder>
```

不新增 `user` 或 `system` 消息，因此每个 assistant 的 `tool_calls` 后仍紧跟对应 ID 的 tool 消息，协议顺序与 S04 一致。达到模型调用上限的那一批不附加，因为不会再请求模型。PostToolUse Hook 看到的是工具原始结果，不含提醒。

## 4. 与权限和 Hook 的关系

| 机制 | `todo_write` 的行为 |
| --- | --- |
| S03 权限 | `PermissionPolicy` 直接允许，不逐次确认；它只修改内存 |
| S04 PreToolUse | 照常触发，可拦截；拦截后清单不变 |
| S04 PostToolUse | 照常触发，结果为渲染后的清单或工具错误 |
| S04 Stop | 不变；有未完成项时模型给出最终回复仍触发 Stop |

同一批中 `todo_write` 与写文件调用并存时，写文件仍需确认，被拒绝也不影响清单更新。

## 5. 组装

`TodoMain.createLoop` 复用 `app.HooksMain.createHooks` 生成共享 Hook 链（内置日志 → 追加 Java Hook → 信任后的用户 JSON Hook），再通过 `ToolUseMain.createTools(..., extraTools)` 把 `TodoWriteTool` 追加为第六个工具，最后把同一个 `TodoList` 交给 `TodoReminder` 注入 `ChatLoop`。测试重载可传入临时项目目录、临时信任存储和 `TodoList` 实例以检查状态。

## 6. 练习

1. 启动后输入：“在 playground 新建 notes 目录，写入三个文件 a.txt、b.txt、c.txt，再列出目录并读取 b.txt。”观察模型是否先调用 `todo_write`，以及清单状态的变化。写文件仍会逐个确认。
2. 输入简单问题（如“1+1 等于几”），确认模型不调用 `todo_write`，也没有提醒。
3. 在 `s05/.agents/hook.json` 中为 `PreToolUse` 配置 `matcher: "todo_write"` 且 `exit 2` 的命令，确认后观察模型拿到拒绝结果后如何处理。
4. 修改 `TodoReminder.DEFAULT_THRESHOLD` 为 1，观察每批未更新都提醒的效果，再改回。

## 7. 验证

自动测试覆盖清单校验与失败不改状态、工具参数校验、提醒计数与清零、提醒附加位置与消息顺序、跨轮保留、上限批次不提醒、Hook 拦截、`todo_write` 免确认而写文件仍需确认。全部使用模拟模型与临时目录，不需要 API 密钥。模型是否主动、合理地使用清单取决于具体模型，需要真实服务单独验收。
