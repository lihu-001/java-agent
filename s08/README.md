# S08：Context Compact

复制 S07 完整能力，新增上下文压缩：每次请求模型前整理历史，较早的长工具结果以占位说明代替；对话超过阈值或模型调用 `compact` 时，先把完整记录保存到工作目录 `.transcripts/`，再请模型总结，历史替换为“系统提示词 + 摘要”。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理、S07 技能保持不变。

## 包结构

根包只保留默认入口 `CompactMain`，S07 的 `SkillMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s08
├── CompactMain               # 默认入口，组装完整 S08
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain / SkillMain
├── config                    # AgentConfig（新增两项压缩配置）、S08ProjectDirectory
├── loop                      # ChatLoop：请求前调用压缩器
├── model / tool              # 与 S07 相同
├── permission                # compact 直接放行
├── hook / todo / subagent / skill   # 与 S07 相同，配置固定在 s08/.agents/
└── compact                   # 本课新增：MicroCompact、TranscriptStore、ContextCompactor、CompactTool
```

学习顺序：[compact/MicroCompact](src/main/java/dev/learnagent/s08/compact/MicroCompact.java) → [compact/ContextCompactor](src/main/java/dev/learnagent/s08/compact/ContextCompactor.java) → [loop/ChatLoop](src/main/java/dev/learnagent/s08/loop/ChatLoop.java) → [compact/CompactTool](src/main/java/dev/learnagent/s08/compact/CompactTool.java) → [CompactMain](src/main/java/dev/learnagent/s08/CompactMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `compact.MicroCompact` | 新增：保留最近 N 个工具结果，更早且超过 200 字符的换成占位说明；`task`、`load_skill`、`todo_write`、`compact` 结果不替换 |
| `compact.TranscriptStore` | 新增：把完整历史写成 JSONL，只创建新文件 |
| `compact.ContextCompactor` | 新增：请求前执行微压缩；超过阈值或有 `compact` 登记时保存记录、请求总结、替换历史 |
| `compact.CompactTool` | 新增：`compact(focus?)` 只登记请求，本批工具结果补齐后执行 |
| `loop.ChatLoop` | 新构造参数 `ContextCompactor`，为 null 时与 S07 相同；每次请求前调用压缩器 |
| `config.AgentConfig` | 新增 `compact.threshold-chars`（默认 50000）、`compact.keep-tool-results`（默认 3） |
| `permission.PermissionPolicy` | `compact` 直接允许 |
| `app.SkillMain` | 由根包移入 `app`，作为 S07 入口保留 |
| `CompactMain` | 默认入口：主代理九个工具；子代理与 S07 相同，不压缩 |

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s08 -q compile exec:java`。想尽快看到压缩，可在 `config.properties` 临时设置 `compact.threshold-chars=3000`。

完整记录含对话原文和工具结果，可能包含敏感内容，位于工作目录 `.transcripts/`，不会自动清理（仓库已在 `.gitignore` 中忽略）。阈值按字符粗略估算，不是服务商的 token 计数。

详细讲解见 [课程文档](../docs/lessons/s08-context-compact.md)。
