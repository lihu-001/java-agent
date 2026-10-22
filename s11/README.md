# S11：Background Tasks 后台任务

复制 S10 完整能力，新增后台执行：模型用 `background_run` 把耗时命令（安装依赖、构建、长时间测试）放到后台线程，立即拿到任务 ID 继续工作；命令结束后，结果在下一次请求模型前自动附加到最新一条消息，不需要轮询。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理、S07 技能、S08 压缩、S09 记忆、S10 任务图保持不变。

## 包结构

根包只保留默认入口 `BackgroundMain`，S10 的 `TaskMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s11
├── BackgroundMain            # 默认入口，组装完整 S11
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain / SkillMain / CompactMain / MemoryMain / TaskMain
├── config                    # AgentConfig 增加 background.timeout-seconds
├── loop                      # ChatLoop 增加可选 BackgroundManager，为 null 时与 S10 相同
├── permission                # background_run 与 shell 同规则，background_check 直接允许
├── model / tool / hook / todo / subagent / skill / compact / memory / task   # 与 S10 相同，配置固定在 s11/.agents/
└── background                # 本课新增：BackgroundStatus、BackgroundTask、BackgroundManager、BackgroundTools
```

学习顺序：[background/BackgroundManager](src/main/java/dev/learnagent/s11/background/BackgroundManager.java) → [background/BackgroundTools](src/main/java/dev/learnagent/s11/background/BackgroundTools.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s11/loop/ChatLoop.java) 中标注 S11 的两处 → [BackgroundMain](src/main/java/dev/learnagent/s11/BackgroundMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `background.BackgroundStatus` | 新增：`running`、`completed`、`failed`、`timed_out`、`cancelled` |
| `background.BackgroundTask` | 新增：任务快照（ID、命令、状态、完整结果、耗时） |
| `background.BackgroundManager` | 新增：守护线程执行、并发与总数上限、完成通知队列、会话结束取消 |
| `background.BackgroundTools` | 新增：`background_run`、`background_check` |
| `loop.ChatLoop` | 增加可选 `BackgroundManager`：每次请求模型前取出通知附加到最后一条消息；首次请求失败时放回；会话结束时取消运行中的命令 |
| `config.AgentConfig` | 新增 `background.timeout-seconds`，默认 600 |
| `permission.PermissionPolicy` | `background_run` 与 `shell` 相同的硬拒绝和逐次确认；`background_check` 直接允许 |
| `app.TaskMain` | 由根包移入 `app`，作为 S10 入口保留 |
| `BackgroundMain` | 默认入口：主代理二十个工具，系统提示词附后台规则；子代理与 S10 相同，没有后台工具 |

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s11 -q compile exec:java`。在练习目录准备一个耗时命令，例如让模型“在后台运行 `Start-Sleep 15; Write-Output 构建完成`，同时帮我读一下 example.txt”，确认后观察：工具结果立即返回 `bg_1`，模型继续读文件；约 15 秒后终端出现 `后台> bg_1 completed`，下一次请求时模型看到 `<background-results>`。

后台任务只在本次会话内存中，输入 exit 时仍在运行的命令会被终止。

详细讲解见 [课程文档](../docs/lessons/s11-background-tasks.md)。
