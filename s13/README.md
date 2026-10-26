# S13：Agent Teams 团队协作

复制 S12 完整能力，新增**常驻队友**：Lead（原来的主 Agent）用 `spawn_teammate` 创建最多 4 个队友，每个队友是独立线程上的完整 Agent，有自己的历史、任务清单和压缩器，与 Lead 并行工作。成员之间通过工作目录 `.mailboxes/` 收件箱通信，在 `.tasks/` 中认领任务；主线程是唯一读取用户输入的线程，队友需要确认的工具调用排队交给主线程询问用户。S03 权限、S04 Hook 与信任机制、S05—S12 的全部能力保持不变。

**团队模式默认关闭。** 在 `config.properties` 中设 `team.enabled=true` 才启用；关闭时（默认）S13 与 S12 完全相同：没有团队规则和四个团队工具，启动时也不清空收件箱、不退回任务，简单任务没有额外的请求和 token 开销。这与 Claude Code 用默认关闭的开关（`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS`）区分单 Agent 与团队模式的做法一致。拼错的值（如 `yes`）会报配置错误，而不是悄悄关闭。

## 包结构

根包只保留默认入口 `TeamMain`，S12 的 `ScheduleMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s13
├── TeamMain                  # 默认入口：组装 Lead、团队与主线程控制台
├── app                       # ToolUseMain … BackgroundMain / ScheduleMain（前几课入口）
├── task                      # TaskStore 加进程内锁 + 文件锁，新增 claimNext / release；队友版任务工具
├── permission                # spawn_teammate 逐次确认；send_message / list_teammates / shutdown_teammate 直接允许
├── loop / model / tool / hook / todo / subagent / skill / compact / memory / background / schedule   # 与 S12 相同
└── team                      # 本课新增
    ├── TeamMessage           # 收件箱中的一条消息（from、to、type、content、requestId）
    ├── MessageBus            # .mailboxes/<name>.jsonl 文件收件箱 + Condition 唤醒
    ├── TeamConsole           # 主线程事件队列：终端行、收件箱唤醒、队友确认请求
    ├── TeammatePermission    # 队友的权限检查：ASK 交给 TeamConsole
    ├── TeammateSetup         # 组成一个队友 Agent 的部件
    ├── TeammateAgent         # 队友的一轮 Agent 循环，历史跨轮保留
    ├── Teammate              # 常驻线程：WORK ⇄ IDLE、自动认领、关闭协议
    ├── TeamManager           # 创建、消息、关闭、成员表、Lead 收件箱渲染
    ├── TeamTools             # spawn_teammate / send_message / list_teammates / shutdown_teammate
    ├── WorktreeManager       # 每个队友一个 git worktree：.worktrees/<name>，分支 wt/<name>
    └── PrefixWriter          # 队友输出加 "[name]" 前缀，整行写入共享终端
```

学习顺序：[team/MessageBus](src/main/java/dev/learnagent/s13/team/MessageBus.java) → [team/TeamConsole](src/main/java/dev/learnagent/s13/team/TeamConsole.java) → [team/Teammate](src/main/java/dev/learnagent/s13/team/Teammate.java) → [team/TeamManager](src/main/java/dev/learnagent/s13/team/TeamManager.java) → [task/TaskStore](src/main/java/dev/learnagent/s13/task/TaskStore.java) 的 `claim` / `claimNext` → [TeamMain](src/main/java/dev/learnagent/s13/TeamMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `team.*` | 新增，见上 |
| `task.TaskStore` | 所有修改在“进程内可重入锁 + `.tasks/.lock` 文件锁”内重读、校验、写入；新增 `claim(id, owner, single)`（一次只持有一个任务）、`claimNext`（发现与认领同在锁内）、`release`、`releaseAllExcept`、`inProgress` |
| `tool.file.GlobTool` | 跳过工作目录根下的 `.worktrees/`，Lead 查找时不会搜到队友的副本 |
| `task.TaskTools` | 新增 `createForTeammate`：只有 `task_list` / `task_get` / `task_claim` / `task_complete`，认领一次一个 |
| `permission.PermissionPolicy` | `spawn_teammate` 逐次确认；三个团队消息/查看/关闭工具直接允许 |
| `app.ScheduleMain` | 由根包移入 `app`，作为 S12 入口保留 |
| `config.AgentConfig` | 新增 `team.enabled`（默认 false，只接受 true/false）与 `withWorkDirectory`、`withTeamEnabled` |
| `TeamMain` | `team.enabled=false` 时直接组装 S12 会话；为 true 时 Lead 三十个工具（S12 的二十六个加四个团队工具），系统提示词附团队规则 |

`ChatLoop` 不修改：Lead 的 `readLine` 由 `TeamConsole` 提供，没有用户输入时也能被队友消息唤醒。

## 团队怎么工作

- **线程**：主线程运行 Lead，是唯一做决定、读取用户回答的线程；一个守护线程只把终端读到的行搬进事件队列（`System.in` 的阻塞读取无法被唤醒）。每个队友一个命名守护线程 `teammate-<name>`。
- **唤醒全靠事件**：发消息给 Lead 时放一个唤醒事件进主线程队列，Lead 不等用户输入就开始新的一轮，消息作为 `<team-events>` 交给模型；队友空闲时阻塞在收件箱的 `Condition` 上，有消息、任务图变化（`task_create` / `task_update` / `task_complete` 之后）时立即醒来。30 秒超时只是兜底，覆盖其他进程修改 `.tasks/` 的情况。
- **收件箱**：`.mailboxes/<name>.jsonl`，发送追加一行，读取后删除；没有读取收件箱的工具，运行时在下一轮前自动送达。启动时清空上次会话遗留的收件箱。
- **队友循环**：WORK 时先处理收件箱（关闭请求优先），跑一轮 Agent，结束后把最终回复作为 `result`（失败为 `error`）发给 Lead；IDLE 时先 `claimNext` 自动认领一个可开始的任务，没有就等待。
- **通信**：自由消息是网状的，`send_message` 可发给 lead 或任何活跃队友，不经过 Lead；关闭是 Lead 与队友之间带 `requestId` 的请求—响应，Lead 只接受与已发请求配对的响应。
- **认领**：两个队友抢同一任务时只有一个成功；队友一次只持有一个进行中的任务，不能创建任务或改依赖。owner 由程序固定为队友名，Lead 仍是 `agent`。
- **权限**：队友与 Lead 同一套规则。允许和硬拒绝在队友线程本地决定；需要确认时队友阻塞，主终端显示 `权限确认> [alice] …` 由用户回答。Lead 正在等自己的确认回答时，队友请求排队，回到提示符后依次询问。
- **退出**：`exit`、EOF 或异常结束时，先拒绝所有等待中的确认，再中断队友线程并等待最多 5 秒；队友手上未完成的任务退回 pending。下次启动时，被已不存在的队友占着的任务也会退回 pending。

队友是完整 Agent：五个基础工具、`todo_write`、`task` 子代理、`load_skill`、`compact`、`memory_read`、四个任务工具和 `send_message`，共十五个。没有后台与定时工具（后台工作不能比 Lead 的会话活得更久），没有记忆写入，也不能创建队友（不支持嵌套团队）。用户 JSON Hook 只确认一次，Lead 与所有队友共用。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s13 -q compile exec:java`。先在 `config.properties` 中设 `team.enabled=true`，再让模型组队完成一件可拆分的事，例如“建两个队友，一个统计 playground 里的文件数，一个读 example.txt 并总结”，确认组队后观察：队友输出带 `[name]` 前缀；队友的 Shell 调用在主终端请求确认；不输入任何内容，Lead 也会在队友完成后收到 `<team-events>` 并汇总。

队友越多，并发模型请求越多，费用随人数线性增长。

## 可选：每个队友一个 git worktree

默认所有队友与 Lead 共用工作目录，两个队友改同一个文件会互相覆盖。`agent.work-dir` 指向一个 git 仓库的**根目录**时，`spawn_teammate` 可传 `worktree=true`：

- 程序用参数列表调用 git（不经过 Shell，30 秒超时），执行 `git worktree add -b wt/<name> .worktrees/<name> HEAD`，并把 `/.worktrees/` 写进该仓库的 `.git/info/exclude`，不改动项目的 `.gitignore`。
- 这个队友的文件工具、Shell 和路径检查都以 `.worktrees/<name>` 为根；`.tasks/`、`.mailboxes/`、记忆仍在主目录共享。
- 每个队友一个 worktree，而不是每个任务一个；会话结束后 worktree 与分支保留，下次同名队友直接复用。
- 工作目录不是 git 仓库、只是仓库中的子目录、处于分离 HEAD、或分支/目录已被占用时，`spawn_teammate` 报错并说明原因，不会退回共用目录，也不会留下线程或认领。默认的 `playground` 是本仓库的子目录，所以默认不能用 worktree。

合并流程写在提示词里：队友每个任务开始前 `git merge <base>` 同步，做完后提交、再同步并解决冲突、跑测试，然后 `task_complete`；它的结果附上工作分支，Lead 在主目录 `git merge --no-ff wt/<name>` 合并并再跑测试。这些 git 命令都是 Shell 调用，逐次由你确认。队友解决不了的冲突用 `git merge --abort` 放弃并上报，由你决定。

worktree 从 HEAD 检出：主目录未提交的改动队友看不到；被忽略的文件（`config.properties`、`target/`、依赖目录）也不在 worktree 中，需要时由队友自行准备。worktree 只隔离文件与分支，不是安全沙箱。

手动体验时请用一个单独的练习仓库，不要把 `agent.work-dir` 指向 java-agent 根目录（`config.properties` 里有 API 密钥）：

```powershell
mkdir C:\tmp\team-demo; cd C:\tmp\team-demo
git init -b main; Set-Content -Encoding utf8 readme.txt "hello"; git add .; git commit -m init
# 根 config.properties：agent.work-dir=C:/tmp/team-demo
git worktree list; git branch          # 运行后查看 .worktrees/<name> 与 wt/<name>
```

详细讲解见 [课程文档](../docs/lessons/s13-agent-teams.md)。
