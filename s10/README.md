# S10：Task System 持久化任务

复制 S09 完整能力，新增持久化任务图：模型用六个 `task_*` 工具把多步骤工作拆成任务，保存到工作目录 `.tasks/`，每个任务一个 JSON 文件；`blockedBy` 记录前置任务，全部完成后才能认领，跨会话用 `task_list` 恢复进度。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理、S07 技能、S08 压缩、S09 记忆保持不变。

## 包结构

根包只保留默认入口 `TaskMain`，S09 的 `MemoryMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s10
├── TaskMain                  # 默认入口，组装完整 S10
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain / SkillMain / CompactMain / MemoryMain
├── config / loop / model / tool     # 与 S09 相同，ChatLoop 不修改
├── permission                # 六个任务工具直接允许
├── hook / todo / subagent / skill / compact / memory   # 与 S09 相同，配置固定在 s10/.agents/
└── task                      # 本课新增：TaskStatus、TaskRecord、TaskStore、TaskTools
```

学习顺序：[task/TaskRecord](src/main/java/dev/learnagent/s10/task/TaskRecord.java) → [task/TaskStore](src/main/java/dev/learnagent/s10/task/TaskStore.java) → [task/TaskTools](src/main/java/dev/learnagent/s10/task/TaskTools.java) → [TaskMain](src/main/java/dev/learnagent/s10/TaskMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `task.TaskStatus` | 新增：`pending`、`in_progress`、`completed` |
| `task.TaskRecord` | 新增：`id`、`subject`、`description`、`status`、`owner`、`blockedBy` |
| `task.TaskStore` | 新增：`.tasks/task_xxxxxxxx.json` 读写、依赖校验（存在、非自身、无环）、认领、完成与解锁计算 |
| `task.TaskTools` | 新增：`task_create`、`task_update`、`task_list`、`task_get`、`task_claim`、`task_complete` |
| `permission.PermissionPolicy` | 六个任务工具直接允许 |
| `app.MemoryMain` | 由根包移入 `app`，作为 S09 入口保留 |
| `TaskMain` | 默认入口：主代理十八个工具，系统提示词附任务规则；任务内容不进系统提示词；子代理与 S09 相同，没有任务工具 |

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s10 -q compile exec:java`。让模型“把实现用户注册拆成建表、接口、测试三个任务并按依赖推进”，中途退出；再次启动时终端显示 `任务> .tasks 中有 3 个任务，已完成 N 个`，说“继续”即可从文件恢复。

任务是工作目录下的普通 JSON 文件，可直接查看或修改。根 `.gitignore` 已忽略 `.tasks/`。

详细讲解见 [课程文档](../docs/lessons/s10-task-system.md)。
