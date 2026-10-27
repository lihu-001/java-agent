# S16：Workflow Runtime 持久化工作流

复制 S15 的全部能力。本课增加 Workflow Runtime：把"步骤现在就能确定"的多步任务声明为**持久化工作流**——有序的提示词步骤序列，运行时复用子代理逐步骤执行，**每步完成即落盘检查点**；进程退出、中断或某步失败后，再次运行从断点继续（断点续跑）。

与 S10 任务系统的分工：`.tasks/` 记录"要交付什么、谁在做"（数据，模型手动认领完成）；`.workflows/` 记录"按这个顺序做"（执行语义，运行时自动执行）。与 S06 `task` 的区别：task 的步骤散落在对话里，由模型自己记账；工作流的步骤与每步结果持久在文件里，跨会话可查、可续。

- **声明**：`workflow_create(name, steps)` 创建 pending 工作流（最多 20 步，每步说明 ≤4000 字符），保存在工作目录 `.workflows/workflow_xxxxxxxx.json`，文件是唯一数据源。
- **执行**：`workflow_run(id)` 从第一个未完成步骤开始逐步执行；每步是一个独立子代理（看不到主对话），**前序步骤的结果自动附在后续步骤的说明里**（每条截断到 2000 字符）。
- **检查点**：每步开始（标记 running）与结束（写入 result）都落盘；子代理模型请求失败或达到调用上限时该步失败、工作流 `failed` 并停止；`InterruptedException` 时步骤回到 pending、工作流 `paused`。
- **恢复**：启动时把遗留 `running` 修复为 `paused`（其中 running 的步骤回到 pending）；`workflow_run` 对 pending/paused/failed 的工作流从断点继续，**失败的步骤会重跑**——副作用可能重复，步骤说明应写成可重复执行。
- **终态**：全部步骤完成 → `completed`；`workflow_cancel` 把 pending/paused/failed 标记为 `cancelled`（终态，不能再运行）。

**上一课入口 `app.HarnessMain` 行为与 S15 完全相同**（不接 `WorkflowStarter`，没有工作流工具与启动修复）。

## 包结构

根包只保留默认入口 `WorkflowMain`，S15 的 `HarnessMain` 移入 `app` 作为上一课入口：

```text
dev.learnagent.s16
├── WorkflowMain               # 默认入口：构造 WorkflowStarter.of(config) 交给 TeamMain 组装
├── app                        # ToolUseMain … ScheduleMain / TeamMain / HarnessMain（前几课入口）
├── permission / loop / model / tool / hook / todo / subagent / skill / compact / memory / task
│   / background / schedule / team / mcp / harness              # 与 S15 相同（部分有 S16 增量）
└── workflow                   # 本课新增
    ├── StepStatus             # 步骤四状态：pending / running / completed / failed
    ├── WorkflowStatus         # 工作流六状态：pending / running / paused / completed / failed / cancelled
    ├── WorkflowStep           # 单步记录：prompt + status + result + 起止时间
    ├── WorkflowRecord         # 工作流记录：id / name / status / steps / createdAt / updatedAt / lastError
    ├── WorkflowStore          # .workflows/ 文件存储：每次操作重读、临时文件原子替换、启动修复、解析校验
    ├── WorkflowRunner         # 执行器：顺序执行、每步检查点、失败停止、中断暂停、前序结果传递
    ├── WorkflowTools          # 五个工具与系统提示词规则
    ├── WorkflowBinding        # 组装绑定：追加的工具与规则
    └── WorkflowStarter        # 组装回调：bind(runner, output)；null 时与 S15 相同
```

学习顺序：[workflow/WorkflowStore](src/main/java/dev/learnagent/s16/workflow/WorkflowStore.java) → [workflow/WorkflowRunner](src/main/java/dev/learnagent/s16/workflow/WorkflowRunner.java) → [workflow/WorkflowTools](src/main/java/dev/learnagent/s16/workflow/WorkflowTools.java) → [workflow/WorkflowStarter](src/main/java/dev/learnagent/s16/workflow/WorkflowStarter.java) → [WorkflowMain](src/main/java/dev/learnagent/s16/WorkflowMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `workflow.*` | 新增，见上 |
| `permission.PermissionPolicy` | 五个 `workflow_*` 工具 ALLOW（只读写 `.workflows`；步骤经子代理执行，其工具调用另行检查权限，与 `task` 同一逻辑） |
| `app.ScheduleMain` / `app.TeamMain` | 新增带 `WorkflowStarter` 的 `createSession` 重载：子代理运行器就绪后 `bind` 得到工具与规则，追加在 MCP 之前/规则附在 MCP 规则之后；null 时提示词与 S15 逐字相同。团队模式只有 Lead 有工作流工具，子代理与队友没有 |
| `WorkflowMain` | 默认入口：与 `app.HarnessMain` 相同的装配，外加 `WorkflowStarter.of(config)`；启动时打印修复与统计 |

`ChatLoop`、`SubagentRunner`、`ToolRegistry` 本课一行未改：工作流是普通工具加系统提示词规则，步骤执行复用 S06 的子代理循环。

## 运行时行为

- **步骤执行**：`workflow_run` 同步执行（与 `task` 相同，在调用线程上）；每个子代理单独计数 `agent.max-model-calls`，其写文件与 Shell 照常逐次请求用户确认。
- **输出**：`workflow_run` 返回全部步骤的最新状态与结果摘要（每条截断 2000 字符，整体截断 12000）；`workflow_list` 一行一个工作流，`workflow_get` 返回完整 JSON。
- **启动**：`工作流> .workflows 中有 N 个工作流，已完成 X 个`；有遗留 running 时先打印修复说明。目录不存在视为没有工作流，单个文件无效只告警跳过，路径不是目录报错。
- **上限**：最多 20 个工作流、每个最多 20 步；文件 ≤64 KiB 严格 UTF-8；ID 为 `workflow_` 加 8 位随机小写十六进制，排他创建。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s16 -q compile exec:java`。让模型"用工作流分两步统计 playground 的文件数再写总结"，观察 `workflow_create` → `workflow_run` 的逐步执行与检查点；中途 `exit` 再启动，`workflow_run` 会从上次中断的步骤继续。

协议细节和设计取舍见 [课程文档](../docs/lessons/s16-workflow-runtime.md)。
