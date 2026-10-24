# S12：Cron Scheduler 定时计划

复制 S11 完整能力，新增**持久化定时计划**：模型用 `schedule_create` 按五段 cron 登记一条 Shell 命令，程序在本次进程运行期间每 15 秒检查一次，到点交给后台执行；执行状态和输出写回工作目录 `.schedules/`，重启后计划仍在，用 `schedule_get` 或 `schedule_list` 查看。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理、S07 技能、S08 压缩、S09 记忆、S10 任务图、S11 后台任务保持不变。

## 包结构

根包只保留默认入口 `ScheduleMain`，S11 的 `BackgroundMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s12
├── ScheduleMain              # 默认入口，组装完整 S12 并启动调度线程
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain / SkillMain / CompactMain / MemoryMain / TaskMain / BackgroundMain
├── config                    # AgentConfig 增加 scheduler.poll-seconds
├── loop                      # 与 S11 相同，本课不修改 ChatLoop
├── permission                # schedule_create 与 shell 同规则；查询/暂停直接允许，恢复/删除逐次确认
├── background                # 增加完成/关闭监听器，支持不带 source 的用户任务与带 source 的定时任务
├── model / tool / hook / todo / subagent / skill / compact / memory / task   # 与 S11 相同，配置固定在 s12/.agents/
└── schedule                  # 本课新增：CronExpression、ScheduleRunStatus、ScheduleRecord、ScheduleStore、ScheduleManager、ScheduleTools
```

学习顺序：[schedule/CronExpression](src/main/java/dev/learnagent/s12/schedule/CronExpression.java) → [schedule/ScheduleStore](src/main/java/dev/learnagent/s12/schedule/ScheduleStore.java) → [schedule/ScheduleManager](src/main/java/dev/learnagent/s12/schedule/ScheduleManager.java) → [schedule/ScheduleTools](src/main/java/dev/learnagent/s12/schedule/ScheduleTools.java) → [ScheduleMain](src/main/java/dev/learnagent/s12/ScheduleMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `schedule.CronExpression` | 新增：五段 cron 的下一次触发时间计算；字段解析、校验与匹配委托 Hutool `CronPattern`，本类补日/周“或”语义，最短一分钟 |
| `schedule.ScheduleRunStatus` | 新增：`running`、`completed`、`failed`、`timed_out`、`cancelled` |
| `schedule.ScheduleRecord` | 新增：计划不可变快照（ID、名称、cron、命令、启停、下一次、上次运行） |
| `schedule.ScheduleStore` | 新增：工作目录 `.schedules/schedule_xxxxxxxx.json` 的读写，文件是唯一数据源，临时文件原子替换 |
| `schedule.ScheduleManager` | 新增：启动修复、单守护线程轮询、到点触发、暂停/恢复/删除、结果回写 |
| `schedule.ScheduleTools` | 新增：`schedule_create`、`schedule_list`、`schedule_get`、`schedule_pause`、`schedule_resume`、`schedule_delete` |
| `background.BackgroundManager` | 增加完成监听器和关闭监听器；定时任务不占每会话 20 个用户任务额度，但共享同时最多 4 个的上限；不出现在 `background_check`，结果不进入对话通知 |
| `background.BackgroundCompletion` | 新增：后台任务结束事件，供调度器识别是哪条计划完成 |
| `config.AgentConfig` | 新增 `scheduler.poll-seconds`，默认 15 |
| `permission.PermissionPolicy` | `schedule_create` 与 `shell` 共用硬拒绝和逐次确认；`schedule_list` / `schedule_get` / `schedule_pause` 直接允许；`schedule_resume` / `schedule_delete` 逐次确认 |
| `app.BackgroundMain` | 由根包移入 `app`，作为 S11 入口保留 |
| `ScheduleMain` | 默认入口：主代理二十六个工具，系统提示词附定时计划规则；子代理仍只有六个基础工具 |

## 定时计划怎么工作

- **持久化**：每个计划一个 JSON 文件，放在工作目录 `.schedules/`。计划文件是唯一数据源，每次操作重新读取，手工修改也会生效。
- **只在运行时触发**：调度线程属于当前进程，按 `scheduler.poll-seconds`（默认 15 秒）轮询，不是操作系统计划任务。程序退出后计划不执行，重启时不会补跑错过的时点，只报告错过数量并把下一次推进到未来。
- **启动修复**：上次退出时仍标记为 `running` 的计划改为 `cancelled`，避免永久卡住。
- **无人值守执行**：创建时按 `shell` 规则确认一次，之后每次触发不再询问。命令用独立后台执行器运行，超时读 `background.timeout-seconds`。
- **不重复堆叠**：到点时上一次仍未结束，则跳过一次并在终端提示，下一次继续按 cron 计算。
- **结果写回**：完成状态、结束时间与输出（最多 2000 字符）写入计划文件；不进入 `<background-results>` 对话通知，需要时 `schedule_get` 读取。
- **上限**：最多 50 个计划；定时运行共享后台同时最多 4 个的上限，但不占每会话 20 个用户任务额度；终态的定时记录最多保留 20 条。

cron 使用五段 `分 时 日 月 周`，解析交给 `hutool-cron`；本课只保留五段限制，并在日与周同时受限时按 Vixie cron 的“或”匹配。时间按本机时区解释，最短分辨率一分钟。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s12 -q compile exec:java`。在练习目录让模型创建一个计划，例如“创建一个每分钟执行 `echo tick` 的计划”，确认后观察：`schedule_create` 返回 `schedule_xxxxxxxx` 和下一次时间；保持程序运行，到点后终端出现触发与完成日志；再让它 `schedule_get` 该 ID，查看 `lastStatus`、`lastFinishedAt` 和输出。输入 exit 退出后重新启动，`schedule_list` 仍能看到计划。

计划只在 Agent 进程运行期间触发；需要操作系统级常驻执行时，应由外部服务托管进程或改用系统计划任务。

详细讲解见 [课程文档](../docs/lessons/s12-cron-scheduler.md)。
