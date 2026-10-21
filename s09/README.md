# S09：Memory 跨会话记忆

复制 S08 完整能力，新增跨会话记忆：模型用 `memory_write` 把以后仍有用的信息保存到工作目录 `.memory/`，每条一个 Markdown 文件；下次启动时只把名称、类型和描述组成的索引放进系统提示词，正文由 `memory_read` 按需读取。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理、S07 技能、S08 压缩保持不变。

## 包结构

根包只保留默认入口 `MemoryMain`，S08 的 `CompactMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s09
├── MemoryMain                # 默认入口，组装完整 S09
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain / SkillMain / CompactMain
├── config / loop / model / tool     # 与 S08 相同，ChatLoop 不修改
├── permission                # memory_read 放行，memory_write / memory_delete 逐次确认
├── hook / todo / subagent / skill / compact   # 与 S08 相同，配置固定在 s09/.agents/
└── memory                    # 本课新增：MemoryType、MemoryEntry、MemoryStore、三个记忆工具
```

学习顺序：[memory/MemoryStore](src/main/java/dev/learnagent/s09/memory/MemoryStore.java) → [memory/MemoryWriteTool](src/main/java/dev/learnagent/s09/memory/MemoryWriteTool.java) → [memory/MemoryReadTool](src/main/java/dev/learnagent/s09/memory/MemoryReadTool.java) → [MemoryMain](src/main/java/dev/learnagent/s09/MemoryMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `memory.MemoryType` | 新增：`user`、`feedback`、`project`、`reference` 四类 |
| `memory.MemoryEntry` | 新增：索引条目，只有名称、类型、描述与文件路径 |
| `memory.MemoryStore` | 新增：扫描 `.memory/*.md`、渲染索引、读取正文、整体覆盖写入（临时文件替换）、删除 |
| `memory.MemoryReadTool` / `MemoryWriteTool` / `MemoryDeleteTool` | 新增：`memory_read(name)`、`memory_write(name,type,description,content)`、`memory_delete(name)` |
| `permission.PermissionPolicy` | `memory_read` 直接允许；`memory_write`、`memory_delete` 逐次确认 |
| `app.CompactMain` | 由根包移入 `app`，作为 S08 入口保留 |
| `MemoryMain` | 默认入口：主代理十二个工具，系统提示词附记忆规则与启动时的索引；子代理与 S08 相同，没有记忆工具 |

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s09 -q compile exec:java`。告诉模型一个长期偏好（例如“以后测试都用 JUnit 5”），确认保存后退出；再次启动时终端会显示 `记忆> 已加载索引 1 条`。

记忆是工作目录下的普通 Markdown 文件，可直接查看、修改或删除，重启后生效。记忆内容可能包含个人信息，根 `.gitignore` 已忽略 `.memory/`。

详细讲解见 [课程文档](../docs/lessons/s09-memory.md)。
