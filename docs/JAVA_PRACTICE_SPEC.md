# Java Agent 实战规范

当前采用四个独立 Maven 子工程。课次边界以用户重新定义为准：S01 最小文本对话 Loop，不含工具；S02 工具及多工具；S03 权限；S04 Hook。

## 模块边界

| 模块 | 包与默认入口 | 范围 |
| --- | --- | --- |
| s01 | dev.learnagent.s01 / AgentLoopMain | 6 个生产类，输入、模型请求、文本消息与历史 |
| s02 | dev.learnagent.s02 / ToolUseMain | 在对话基础上增加 Shell、文件工具、分派与回传 |
| s03 | dev.learnagent.s03 / PermissionMain | 在工具分派前增加许可策略和逐次确认 |
| s04 | dev.learnagent.s04 / HooksMain | 四类生命周期 Hook 与自定义 Java 回调 |

每课是前一课的源码快照加增量修改，拥有独立源码、测试、POM、配置示例、README。各模块不依赖其他课的 JAR 或源码，不使用共用 Java 核心。根 POM 为 packaging=pom，只聚合四课、管理依赖和插件版本。

这是教学上的有意重复，替代拆分前共用同一循环的设计。每个模块 README 列出相较前一课的新增类与改动点，后续课不能反向改变前一课的可读内容。

## 课程实现约定

S01 的 ChatMessage 只有 role/content；ModelClient 接收消息并返回文本，HTTP 请求无 tools/tool_choice。每次有效输入仅请求一次模型，失败不记录本轮，无工具执行器或 Shell 配置。

S02 增加 AgentTool、ToolDefinition、ToolCall、ToolRegistry、Shell 和四个文件工具，模型客户端支持 tool_calls。循环保存 assistant，执行并补齐每个 ID 的 tool 结果，再请求模型。错误也回传；请求次数、Shell 超时和输出长度有限制。没有权限或 Hook 类。

S03 新增 ToolPermission、PermissionPolicy、PermissionGate、PermissionMain。读和查找默认允许，写/编辑/Shell 逐次确认，越界和示例硬拒绝直接阻止。确认不进入模型历史，批准只针对本次。没有 Hook 类。

S04 新增 hook.AgentHook、hook.AgentHooks、hook.LoggingHook 和根包 HooksMain。四事件为 UserPromptSubmit、PreToolUse、PostToolUse、Stop。两个前置事件可拦截，后置异常不覆盖结果；任务完成仅指本轮正常最终回复，失败、超时、超限、中断和退出不触发 Stop。事件链由本模块循环和工具分派器共用，自定义回调只注册一次。

保留前一课的组装类便于代码对照；启动应使用各模块 POM 的默认入口。例如 s04 默认 HooksMain，直接手动运行保留的 app.ToolUseMain 不会启用权限和 Hook。

## 技术与目录

JDK 17、Maven Wrapper 3.9.3、Hutool、fastjson2、JUnit 5，版本集中管理。各模块有 src/main/java 和 src/test/java，包名与模块名对应。S02—S04 各有 playground，根目录也保留已有用户练习文件。

S04 按职责分包，根包只留 HooksMain：app 放历史入口与组装，config 放配置，loop 放循环，model 放消息与模型客户端，tool 放接口/调用数据/注册表/Shell，tool.file 放文件工具与内部辅助类，permission 放权限，hook 放钩子。测试同步使用对应子包。模块之间仍没有 Java 依赖，S01—S03 的阶段源码不因 S04 整理而修改。

配置只从当前进程工作目录的 config.properties 读取，UTF-8；从根目录执行 -pl 共用根配置，进入子工程运行使用子工程配置。所有层级的 config.properties 和 target/ 被忽略，不把真实凭据复制进新模块。S01 忽略工具配置项。

```powershell
# 全部模块
.\mvnw.cmd clean package

# 单独选择模块，无需安装其他课程
.\mvnw.cmd -pl s01 test
.\mvnw.cmd -pl s02 -q compile exec:java
.\mvnw.cmd -pl s03 -q compile exec:java
.\mvnw.cmd -pl s04 -q compile exec:java

# 在子工程目录内
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

## 测试要求

每个模块保留该阶段完整回归，不因其他课已有测试而省略。S01 验证纯文本协议与历史，S02 增加工具及本地 HTTP 集成，S03 增加权限，S04 增加四事件生命周期测试。自动测试使用模拟模型、本地 HTTP 和临时文件，不需要 API 密钥。

Shell 危险命令只作为待分类字符串；真实执行用无副作用命令。工作区与文本权限规则不是系统沙箱。Unix 分支未在本机验证，Windows 符号链接测试可能因权限跳过。真实模型验收必须单独记录。

## 后续课程

S05 TodoWrite、S06 Subagent、S07 Skill Loading、S08 Context Compact、S09 Memory、S10 Task System、S11 Background Tasks、S12 Cron Scheduler、S13 Agent Teams、S14 MCP Plugin、S15 Integrated Harness、S16 Workflow Runtime、S17 Goal Loop。新增课以当前前一课的完整源码为起点，保留已完成课程快照。

## 模块拆分验证

拆分前已提交 `231f8c0`，包含四类 Hook。旧工程的构建与真实模型记录归档在 [拆分前规范](history/pre-modules-spec.md)。新课次定义与旧记录不同，不能用旧记录替代新模块验收。

本次将最早的纯文本循环恢复为 S01，并保留对截断/过滤响应的错误处理；S02、S03、S04 分别取各阶段完整实现，统一为本模块包名。根工程不再保留生产或测试源码。

2026-10-20，Windows PowerShell、JDK 17.0.2、Maven Wrapper 3.9.3。执行 `.\mvnw.cmd clean package`，父工程及四个模块全部 `BUILD SUCCESS`，分别生成四个普通 JAR：

| 模块 | 测试总数 | 通过 | 跳过 |
| --- | --- | --- | --- |
| S01 | 24 | 24 | 0 |
| S02 | 102 | 101 | 1 |
| S03 | 154 | 153 | 1 |
| S04 | 190 | 189 | 1 |
| 合计 | 470 | 467 | 3 |

没有失败或错误。三个跳过均为各课程快照中的同一类 Windows 符号链接权限测试，不能记作通过。完整阶段测试在各模块重复是有意设计。

另外验证：

1. 根目录分别执行 `-pl s01` 至 `-pl s04` 的 `exec:java`，输入 `exit`，全部正常启动与退出。S01 无工具列表，其余模块各显示五个工具；S04 显示四事件。
2. 进入每个子工程，分别执行 `..\mvnw.cmd -q -DskipTests package` 和 `..\mvnw.cmd -q exec:java`，独立打包与启动均成功，无需安装其他课程。此步骤只验证独立构建，测试结果来自前述全量构建。
3. 子工程启动验证使用新建的临时假配置，输入只有 exit，没有请求外部模型；验证后已删除假配置，未复制或改写根目录真实配置。
4. 检查所有 Java 文件的包和引用，仅属于所在课次；S01 仅六个生产类，S01/S02 无权限类，S01—S03 无 Hook 类；POM 没有跨课程依赖。
5. 所有层级的 target/ 与 config.properties 均正确忽略。各模块文档和配置示例使用 UTF-8。

本次未重新请求真实模型服务，旧工程的真实验收只作历史证据。拆分前的保存点为 `231f8c0`。

## S04 职责分包验证

2026-10-20，将 S04 的 25 个生产类和 14 个测试类迁移到职责子包，根包只保留 HooksMain。更新跨包导入；仅将 WorkspaceFiles.root() 公开给 app 组装层，文件读写与参数解析辅助方法仍为包内可见。默认入口和启动命令不变，S01—S03 源码未修改。

清理 S04 旧编译产物后完成测试，再运行根目录 `.\mvnw.cmd package`，四模块全部成功；仍为 470 项、467 通过、3 项符号链接权限跳过。S04 为 190 项、189 通过、1 跳过。根目录 `-pl s04 -q exec:java` 输入 exit 正常启动并退出，显示四事件及五工具；没有请求真实模型服务。测试与文档引用已同步迁移到新包。
