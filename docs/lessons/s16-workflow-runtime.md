# S16：Workflow Runtime 持久化工作流

本课在独立 s16 子工程中，以 S15 完整源码为起点新增 Workflow Runtime。入口为 `dev.learnagent.s16.WorkflowMain`，S15 的 `HarnessMain` 移入 `app`（不接工作流，行为与 S15 默认入口完全相同）。新增 `s16.workflow` 包（9 个类）与 5 个 `workflow_*` 工具；`ChatLoop`、`SubagentRunner`、`ToolRegistry` 一行未改。

## 1. 为什么需要工作流

到 S15 为止，多步骤任务有三种做法，各有短板：

| 做法 | 步骤记在哪 | 跨会话 | 谁执行 |
| --- | --- | --- | --- |
| S05 `todo_write` | 会话内存 | 否（退出即丢） | 模型按清单自己走 |
| S06 `task` | 对话历史里逐个派发 | 否 | 每次一个子代理 |
| S10 `.tasks/` | 磁盘（任务图） | 是 | 数据而已，模型手动 claim/complete |

问题在"**步骤现在就能确定、不需要根据中间结果改变顺序**"的那类任务：发布流程、批处理、按固定清单检查。用 task 做，步骤顺序和中间结果散落在对话历史里，模型自己记账——对话一压缩（S08）、会话一换，"做到第几步、每步结果是什么"就没了；用任务系统做，它只存数据，执行顺序还是要模型在对话里一步步推进。

工作流把"步骤序列"和"执行进度"都搬进文件，交给**运行时**执行：

```text
workflow_create(name, steps=[说明1, 说明2, 说明3])   →  .workflows/workflow_xxxxxxxx.json（pending）
workflow_run(id)
  ├─ 步骤 1：标记 running → 落盘 → 子代理执行 → 结果落盘
  ├─ 步骤 2：标记 running → 落盘 → 子代理执行 → 结果落盘
  └─ ……（任何一步之后进程都可以死掉，已完成的步骤不会重跑）
全步完成 → completed；某步失败 → failed（可修复后继续）；中断 → paused
```

这就是 durable execution 的最小教学版：**顺序确定、检查点落盘、断点续跑**。步骤本身仍是提示词，交给 S06 的子代理执行——运行时不新增任何执行能力，只管编排与记账。

## 2. 存储：文件是唯一数据源

沿用 S09—S12 的约定：`.workflows/workflow_xxxxxxxx.json`（`workflow_` + 8 位随机小写十六进制，排他创建，冲突重试），每次操作重读，修改先写临时文件再原子替换，读取不跟随符号链接，超过 64 KiB 或字段非法只告警跳过，最多 20 个工作流、每个最多 20 步。字段只有七个：

```json
{
  "id": "workflow_0a1b2c3d",
  "name": "站点检查",
  "status": "paused",
  "steps": [
    {"prompt": "统计 .tasks 中的任务数", "status": "completed",
     "result": "共 3 个……", "startedAt": "…", "finishedAt": "…"},
    {"prompt": "读取汇总并写报告", "status": "pending",
     "result": null, "startedAt": null, "finishedAt": null}
  ],
  "createdAt": "…", "updatedAt": "…", "lastError": null
}
```

状态机是本课的核心语义：

```text
工作流：pending ──run──→ running ──全部完成──→ completed
             │              │ ├──某步失败──→ failed ──run──→（从失败步骤继续）
             │              └──中断/进程退出─→ paused ──run──→（从断点继续）
             └──cancel──────────────→ cancelled（终态）
步骤：  pending ──开始──→ running ──成功──→ completed
                              └──失败──→ failed（重跑时回到 pending）
```

`parse` 做组合校验，防止手改文件造出非法状态：pending 工作流的步骤必须全部 pending；completed 工作流步骤必须全部完成且无 `lastError`；failed 工作流必须有 `lastError`；completed 步骤必须有 result，pending 步骤不能有任何时间与结果。

**启动修复**（`repairAtStartup`）：`workflow_run` 是同步执行，进程退出可能留下 `running` 状态。启动时统一修复为 `paused`，其中 running 的步骤回到 pending——它的结果未知，重跑是工作流模型的根本语义（下一步的"副作用可能重复"由此而来，写在系统提示词规则里）。

## 3. 执行：复用子代理，每步一个检查点

`WorkflowRunner.run(id)` 只有约六十行，要点四个：

1. **可运行状态检查**：pending/paused/failed 可以运行；running（并发防护）、completed、cancelled 拒绝并说明原因。
2. **逐步执行**：每个步骤先标记 running 并落盘（"开始"检查点），再交给 `SubagentRunner.run`——就是 S06 的子代理，每次新建历史、独立计数 `agent.max-model-calls`、工具调用照常逐次确认并经过 Hook。
3. **前序结果传递**：第 N 步的说明自动附上前序已完成步骤的结果（每条截断到 2000 字符），包在 `<前序步骤结果>` 标签里。子代理看不到主对话，这是它唯一的上下文来源。
4. **三种结束**：子代理正常回复 → 步骤 completed、结果落盘（最多 12000 字符）；模型请求失败（IOException）或达到调用上限 → 步骤 failed、工作流 failed 带 `lastError`，停止；`InterruptedException` → 步骤回到 pending、工作流 paused，进度已落盘后重新抛出。

```java
WorkflowStep started = step.markRunning(now);
store.save(current);                        // 开始检查点：进程死在这里，重跑只是重复这一步
result = runner.run(name + " 步骤 " + (index + 1), buildPrompt(current, index));
// 正常：started.finish(COMPLETED, 截断结果, now) → save
// 失败：started.finish(FAILED, 说明, now) + lastError → save → 停止
// 中断：started.reset() → 工作流 paused → save → rethrow
```

**为什么失败算步骤失败而不是工具错误？** `task` 的模型请求失败是转成工具错误文本交回主对话，重试的记账在模型脑子里。工作流的记账在文件里——失败必须写进状态机，"修复后再次 workflow_run"才有断点可续。

**为什么 completed 后不能再 run？** 重跑已完成的工作流等于整段重做，和"断点续跑"语义冲突。要重做请新建工作流；要放弃用 `workflow_cancel`（终态）。

## 4. 工具与权限

| 工具 | 作用 | 权限 |
| --- | --- | --- |
| `workflow_create` | 声明工作流，返回 ID 与步骤列表 | ALLOW |
| `workflow_run` | 从断点执行到结束/失败 | ALLOW |
| `workflow_list` / `workflow_get` | 查看进度 / 完整 JSON | ALLOW |
| `workflow_cancel` | pending/paused/failed → cancelled | ALLOW |

全部 ALLOW、全部经过 PreToolUse/PostToolUse：五个工具只读写 `.workflows` 目录，不执行命令；真正的副作用发生在步骤的子代理里，那里的 `write_file`/`shell` 照常逐次确认——和 S06 `task` 直接放行是同一条逻辑，子代理绕不过权限。

**只有主 Agent（团队模式为 Lead）有工作流工具**，子代理与队友没有（与后台、定时、MCP 同一条边界）。装配上没有给 `TeamMain`/`ScheduleMain` 硬编码工作流，而是沿 S14 `McpManager.Starter` 的模式新增 `WorkflowStarter` 回调：子代理运行器就绪后 `bind(runner, output)` 返回工具与规则；传 null（上一课入口）时提示词逐字不变。工作流步骤用的就是组装点的同一个 `SubagentRunner`，子代理的工具里没有 `workflow_*`，不会递归。

系统提示词规则（`WorkflowTools.RULE`）写明三件事：什么任务适合工作流（步骤现在就能确定）；断点续跑与副作用重复（步骤说明写成可重复执行）；子代理确认不打折。

## 5. 容易出错的地方

- **检查点在开始与结束各落一次盘**。只存"完成"的话，进程死在步骤中间会退回上一个完成点，把已标记的进度丢掉；只存"开始"的话，结果就全在对话里了。
- **中断要先恢复状态再抛出**。`InterruptedException` 被捕后必须先把步骤 reset、工作流标 paused、落盘，再 `throw`——顺序反了，ChatLoop 收到中断直接结束会话，paused 就没写进去。
- **组合校验挡住手改的非法文件**。测试里从 pending 步骤直接 `finish()` 会造出"completed 但没有 startedAt"的步骤——parse 拒绝它，这正是校验存在的意义；构造测试数据要走 `markRunning().finish()` 的正常路径。
- **传给下一步的结果要截断**。存储上限 12000 字符，但传递与汇总各截 2000——20 步全量传递能把子代理的上下文撑爆。
- **团队模式的工具追加在 TeamTools 之后、MCP 之前**，规则附在 MCP 规则之后；`WorkflowStarter` 为 null 时这条链完全不触发，上一课入口的提示词靠结构逐字一致。

## 6. 没有实现的部分

- 步骤并行（DAG）与分支条件——工作流是线性序列，条件编排是模型在对话里的职责；
- 后台执行工作流（`background_run` 只跑 Shell 命令）与跨会话自动续跑——续跑由用户或模型再次调用 `workflow_run` 触发；
- 步骤重试策略（失败自动重试 N 次）——失败即停，修复是人的判断；
- 工作流删除——`cancelled`/`completed` 是终态，文件保留供查阅（与任务系统"没有删除"同一条理由）。

## 7. 练习

1. **完整流程**：让模型"用工作流分两步：统计 playground 的 txt 文件数；把结果写入 summary.txt"。确认第二次的执行，观察 `workflow_run` 逐步输出与子代理确认；`workflow_get` 查看每步结果与起止时间。
2. **断点续跑**：把第二步的说明改成会失败的命令思路，跑一次看 `failed` 与 `lastError`；修复后再次 `workflow_run`，确认第一步没有重跑（子代理只发起一次请求）。
3. **中断恢复**：`workflow_run` 执行中按 Ctrl+C 退出，重启后观察启动修复行"已修复为 paused"，再次运行从中断的步骤继续。
4. **前序结果**：让第一步产出一段特征文字，检查 `workflow_get` 里第二步的说明不含结果，而子代理的实际输入里有 `<前序步骤结果>`——传递只发生在运行时构造的提示词里。
5. **上一课对照**：运行 `-Dexec.mainClass=dev.learnagent.s16.app.HarnessMain`，工具列表应为 26 个（无 `workflow_*`），启动没有 `工作流>` 行，系统提示词除工作流规则外逐字相同。
6. **团队边界**：`team.enabled=true` 时 Lead 有 35 个工具；spawn 一个队友并让它 `workflow_list`，得到"未知工具"错误。

## 8. 验证

S16 模块共 638 项测试（606 项迁移自 S15，全部原样通过；新增 32 项，另在权限策略测试中补充五个工作流工具断言），全量构建十六个模块 0 失败。新增测试与机制的对应关系：

| 测试类 | 覆盖 |
| --- | --- |
| `workflow.WorkflowStoreTest` | 创建与往返、name/steps 校验不落盘、恰好上限、ID 校验、无效文件告警跳过（坏 JSON、id 不一致、未知字段、超大文件）、原子替换无临时文件、启动修复 running→paused 与步骤回 pending、组合状态校验、数量上限、渲染 |
| `workflow.WorkflowRunnerTest` | 顺序执行与开始/结束双检查点（第二步请求时第一步已落盘）、前序结果传递与截断、模型请求失败→failed 且不再执行后续、子代理达上限→failed、从失败步骤续跑不重跑已完成步骤、中断→paused 且步骤回 pending、running/completed/cancelled 拒绝运行 |
| `workflow.WorkflowToolsTest` | 五个工具与规则、create 参数校验、run 返回摘要并落盘、未知/非法 ID、list/get 查看、cancel 生命周期（completed/running 拒绝、cancelled 幂等） |
| `workflow.WorkflowLoopTest` | 31 个工具与规则、上一课入口逐字相同（26 个工具、无工作流行）、create→run 全流程免确认且子代理只有六个工具、失败后跨会话经 `workflow_list` 续跑、中断标记 paused、启动修复与坏文件告警、Hook 顺序与拦截、团队模式 Lead 35 个工具而队友没有 |

Windows 临时文件替换偶发 `AccessDeniedException` 与 S13—S15 记录相同（杀毒或索引进程短暂占用），重跑通过。真实模型下步骤说明质量、断点续跑的实际体验需单独验收；Unix 分支未在本机执行。
