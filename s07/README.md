# S07：Skill Loading

复制 S06 完整能力，新增技能目录与 `load_skill` 工具。启动时扫描本子项目 `.agents/skills/<name>/SKILL.md`，只把技能名称和描述放进系统提示词；模型判断任务匹配时调用 `load_skill` 读取完整说明，正文作为工具结果进入对话。S03 权限、S04 Hook 与信任机制、S05 任务清单、S06 子代理保持不变。

## 包结构

根包只保留默认入口 `SkillMain`，S06 的 `SubagentMain` 移入 `app` 作为上一课组装入口：

```text
dev.learnagent.s07
├── SkillMain                 # 默认入口，组装完整 S07
├── app                       # ToolUseMain / PermissionMain / HooksMain / TodoMain / SubagentMain
├── config                    # AgentConfig、S07ProjectDirectory
├── loop / model / tool       # 与 S06 相同
├── permission                # load_skill 直接放行
├── hook / todo / subagent    # 与 S06 相同，Hook 配置固定为 s07/.agents/hook.json
└── skill                     # 本课新增：SkillFile、Skill、SkillCatalog、LoadSkillTool
```

学习顺序：[skill/SkillFile](src/main/java/dev/learnagent/s07/skill/SkillFile.java) → [skill/SkillCatalog](src/main/java/dev/learnagent/s07/skill/SkillCatalog.java) → [skill/LoadSkillTool](src/main/java/dev/learnagent/s07/skill/LoadSkillTool.java) → [SkillMain](src/main/java/dev/learnagent/s07/SkillMain.java)。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `skill.SkillFile` | 新增：解析 `---` frontmatter（单行 key: value）与正文；文件最多 64 KiB、严格 UTF-8 |
| `skill.Skill` | 新增：技能元数据（名称、描述、文件路径） |
| `skill.SkillCatalog` | 新增：扫描目录，校验名称与目录一致、描述 ≤1024 字符；无效技能告警跳过；最多 32 个；渲染系统提示词中的目录 |
| `skill.LoadSkillTool` | 新增：`load_skill(name)` 返回 `<skill name="...">正文</skill>`，正文超过 12000 字符截断，每次重新读取 |
| `permission.PermissionPolicy` | `load_skill` 直接允许 |
| `app.SubagentMain` | 由根包移入 `app`，作为 S06 入口保留 |
| `config.S07ProjectDirectory` | 由 S06 版本改名，定位本子项目 |
| `SkillMain` | 默认入口：主代理八个工具；子代理五个基础工具加 `load_skill`；两者系统提示词都附带技能目录 |

`loop.ChatLoop` 未修改。

## 示例技能

| 技能 | 用途 |
| --- | --- |
| [code-review](.agents/skills/code-review/SKILL.md) | 只读审查工作目录文件，按三级列出问题 |
| [release-notes](.agents/skills/release-notes/SKILL.md) | 按固定结构撰写中文发布说明，明确要求时才写文件 |

新增技能：建 `.agents/skills/<name>/SKILL.md`，`name` 与目录名一致，重启生效。正文修改无需重启。

## 单独运行

```powershell
..\mvnw.cmd test
..\mvnw.cmd -q compile exec:java
```

或在仓库根运行 `.\mvnw.cmd -pl s07 -q compile exec:java`。技能目录和 Hook 配置都固定读取本模块 `.agents/`，与启动目录无关；Hook 信任记录按项目路径区分。

技能是给模型的说明文本，不能授予权限，技能中要求的写入和 Shell 仍逐次确认。本课不对技能做信任授权，不要放入来源不明的技能。

详细讲解见 [课程文档](../docs/lessons/s07-skill-loading.md)。
