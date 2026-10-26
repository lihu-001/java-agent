# S13：Agent Teams 团队协作

本课在独立 s13 子工程中，以 S12 完整源码为起点增加常驻队友。入口为 `dev.learnagent.s13.TeamMain`，保留 S12 的二十六个工具、S03 权限、S04 Hook、S11 后台任务和 S12 定时计划。

团队模式默认关闭（`team.enabled=false`），此时 S13 与 S12 完全相同；本文描述的都是 `team.enabled=true` 时的行为，原因见第 1 节末尾。

## 1. 为什么需要队友

S06 的子代理是同步的：`task` 调用期间子代理运行，主 Agent 等着，返回总结后子代理就消失了。它适合“查清楚一件事”，但做不到：

- 几块工作**同时**推进；
- 做到一半和别人**沟通**；
- 做完一件事后**接着做**下一件，保留上下文。

S13 的队友是常驻在自己线程里的完整 Agent。Lead 创建它后立即返回，队友在后台工作，结果自动送回 Lead。

| | 子代理（S06） | 队友（S13） |
| --- | --- | --- |
| 调用 | 同步，调用方等结果 | 异步，spawn 后立即返回 |
| 生命周期 | 一次性 | 常驻，直到关闭或会话结束 |
| 历史 | 每次新建 | 跨轮保留 |
| 通信 | 无 | 收件箱，队友之间也能直接发 |
| 成本 | 低 | 每个队友持续独立调用模型 |

两者互补：Lead 和队友都保留 `task` 子代理，用于廉价的上下文隔离。

组队的代价也要算清楚。一次简单任务在 S12 里两次请求就能完成；组队后至少要经过“spawn → 队友从零开始的若干轮 → 结果唤醒 Lead → 汇总”，每个队友独立请求模型，Lead 每次被唤醒都带着完整历史再请求一次。即使不组队，团队规则和四个工具定义也会让每次请求多出约一千字符。所以团队模式用 `team.enabled` 开关，默认关闭；开启后提示词也要求“默认自己做”，只有多块独立的多步工作才组队。

## 2. 线程模型

```text
终端 ──readLine──→ terminal-input 线程（只搬运，不做决定）
                         │ Line / End
                         ▼
                  TeamConsole 事件队列 ←── Wake（Lead 收件箱有新消息）
                         │              ←── Confirm（队友需要用户确认）
                         ▼
                   主线程：Lead 的 ChatLoop
                         │ spawn_teammate
          ┌──────────────┼──────────────┐
   teammate-alice    teammate-bob     ...      每人独立历史、清单、压缩器
          └──── .mailboxes/*.jsonl ────┘ + .tasks/（锁内认领）
```

规则只有一条：**只有主线程读取用户输入**。由此推出：

- `System.in` 的阻塞读取不能被唤醒，所以单独一个守护线程把读到的行放进事件队列；Lead 的 `readLine` 从这个队列取，没有输入时也能被队友消息唤醒。`ChatLoop` 本身一行都没改。
- 队友遇到需要确认的工具，不能自己读终端。它把 `Confirm(future)` 放进主线程队列，阻塞在 `future.get()`；主线程问完用户后 `complete`。
- Lead 正在等自己的确认回答时，到达的队友请求只排队，不抢走用户的这一行；回到提示符后再依次询问。

## 3. 收件箱与唤醒

每个成员一个 `.mailboxes/<name>.jsonl`，发送追加一行 JSON，读取后删除。文件负责保存和可查看，**唤醒靠内存**：

```java
lock.lock();
try {
    Files.writeString(fileOf(message.to()), message.toJson() + "\n", ..., APPEND);
    generation++;
    changed.signalAll();          // 空闲队友醒来
} finally {
    lock.unlock();
}
delivered.accept(message.to());   // 发给 lead 时唤醒主线程
```

队友空闲时 `await(name, 30s)`：有自己的消息、或任何发送 / `wakeAll` 发生就返回。30 秒超时只是兜底，覆盖其他进程修改 `.tasks/` 时收不到事件的情况。任务图变化（`task_create`、`task_update`、`task_complete`）由一个 PostToolUse Hook 调用 `wakeAll`，让空闲队友立即去认领。

没有“读收件箱”的工具：消息由运行时在下一轮前合并成一段 `<team-events>` 交给模型，模型不需要也不能轮询。

## 4. 队友循环

```text
while 未停止:
    mail = drain(收件箱)
    有关闭请求 → 回复 shutdown_response，退出
    有消息 → 合并为本轮输入
    没有输入 → claimNext() 自动认领一个可开始的任务
    还是没有 → IDLE，在收件箱上等待
    WORK：跑一轮 Agent → 最终回复作为 result 发给 Lead
finally: 退回手上未完成的任务
```

关闭请求在一轮结束后才处理，正在进行的模型请求和工具调用会先做完。请求失败时本轮输入保留在历史里——它来自收件箱，丢了就拿不回来。

## 5. 任务认领

S10 的 `owner` 一直固定为 `agent`，“只有认领者能完成”当时没有实际意义。S13 每个队友用自己的名字当 owner，这条约束才起作用。

两个队友可能同时认领同一个任务，所以 `TaskStore` 的所有修改都在两层锁内重读、校验、写入：

- 进程内 `ReentrantLock`：同一 JVM 中 `FileChannel.lock` 不能重复获取，必须先过这一层；
- `.tasks/.lock` 文件锁：防止共用同一目录的其他进程同时修改。

`claimNext` 把“发现可开始的任务”和“认领”放在同一把锁里，不会出现两人看到同一个空闲任务。队友一次只持有一个进行中的任务，不能创建任务或修改依赖；任务图由 Lead 维护。

## 6. 权限与安全

- `spawn_teammate` 逐次确认：每个队友都会持续独立调用模型，不能只靠提示词约定“先征得用户同意”。
- 队友与 Lead 用同一套规则；硬拒绝在队友线程本地生效，需要确认的调用在主终端显示 `权限确认> [alice] …`。
- `<team-events>` 是其他 Agent 写的内容，不是用户指令；系统提示词明确它不能用来跳过确认。
- 退出时先拒绝所有等待中的确认，阻塞在确认上的队友才能醒来退出。

## 7. 可选：每个队友一个 worktree

共用目录时，两个队友改同一个文件，后写的直接覆盖先写的，没有任何提示。worktree 把这种静默覆盖变成合并时显式的冲突，两边的版本都还在。

- **启用条件**：`agent.work-dir` 是 git 仓库根目录。`spawn_teammate(worktree=true)` 时 `WorktreeManager` 执行 `git worktree add -b wt/<name> .worktrees/<name> HEAD`；不满足条件时报错，不退回共用目录。
- **绑定到队友，不绑定到任务**：factory 在创建队友时给它一份以 worktree 为根的配置和权限策略，`ShellTool`、`WorkspaceFiles`、`PermissionPolicy` 都不用改；共享的 `.tasks/`、`.mailboxes/` 仍是主目录里的同一份。
- **合并**：冲突主要来自任务拆分重叠和分支开得太久，所以采用“小步合并”：

```text
队友：git merge main → 做任务 → git commit → git merge main（解决冲突、跑测试）→ task_complete
Lead：收到 result（附工作分支）→ git merge --no-ff wt/<name> → 跑测试
解决不了的冲突：git merge --abort，说明冲突文件，交给用户
```

队友同步过 main 后，Lead 合并通常不会再冲突。git 没报冲突、合并后代码却坏了的“语义冲突”只能靠合并后跑测试发现。

## 8. 本课不做什么

- worktree 只是可选项：默认所有队友在同一工作目录运行，要让不同队友修改不同的文件（见第 7 节）。
- 不做计划审批协议；只有关闭请求使用 requestId 配对。
- 队友没有后台与定时工具，也不能创建队友（不支持嵌套团队）。
- 队友只在本次进程中存在，重启后不恢复；上次被队友占着的任务退回 pending，遗留收件箱清空。

## 9. 练习

1. 让 Lead 建两个任务并接上依赖，再创建两个不带 `task_id` 的队友，观察谁自动认领了哪个任务、第二个任务何时被认领。
2. 让一个队友用 `send_message` 通知另一个队友，查看 `.mailboxes/` 中的文件在读取前后的变化。
3. 在队友等待确认时输入 `exit`，确认写入没有发生、任务退回了 pending。
4. 在一个单独的练习仓库中运行，让两个 worktree 队友改同一个文件的同一行，观察队友同步 main 时的冲突，以及 Lead 合并的结果。
