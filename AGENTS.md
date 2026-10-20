# Repository Guidelines

## 项目结构与模块组织

当前实现 `dev.learnagent.s01` 的对话与 Shell 工具调用循环，入口为 `AgentLoopMain`；`dev.learnagent.s02` 新增文件工具与工具分派，入口为 `ToolUseMain`。`src/main/java` 放应用代码，`src/test/java` 放测试，按需使用 `src/main/resources` 放配置和资源。按课次划分包，测试包路径与被测代码一致。新增模块时，在根目录 README 说明模块职责和入口。扩展课程前阅读 `docs/JAVA_PRACTICE_SPEC.md` 的当前范围。

`dev.learnagent.s03` 在 S02 工具注册表上增加权限规则与终端确认，入口为 `PermissionMain`；三课共用 `s01.ChatLoop`。

`dev.learnagent.s04` 提供四类钩子：用户提示词记录前 `UserPromptSubmit`、工具调用前 `PreToolUse`、工具调用后 `PostToolUse`、任务完成后 `Stop`，入口为 `HooksMain`。Hook 相关类全部放在 `s04`：`AgentHook` 定义接口，`AgentHooks` 统一分派，`LoggingHook` 为默认示例。四课共用 `s01.ChatLoop`，通过 `HooksMain.createLoop` 注册一次自定义 Java Hook。前置放行仍需 S03 权限检查；后置异常不能覆盖结果。任务完成指本轮正常最终回复，超限、失败、中断和退出不触发 `Stop`。

后续课次基于已有实现增量开发，不复制完整循环。S02 共用并扩展 `s01.ChatLoop`，通过 `s01.ToolRegistry` 注册文件工具；原课次入口继续可用。

## 构建、测试与本地开发

使用 JDK 17 和 Maven。`mvn test` 运行测试，`mvn package` 构建普通 JAR，`mvn -q compile exec:java` 启动第一课，加上 `"-Dexec.mainClass=dev.learnagent.s02.ToolUseMain"` 启动第二课。Windows 可将 `mvn` 替换为 `.\mvnw.cmd` 使用项目包装器。真实模型配置见 README，自动测试无需 API 密钥。

第三课使用 `"-Dexec.mainClass=dev.learnagent.s03.PermissionMain"`。权限测试中的危险命令仅作为待分类字符串，不能实际执行；执行测试使用临时文件和无副作用的 Shell 命令。

第四课使用 `"-Dexec.mainClass=dev.learnagent.s04.HooksMain"`。仅运行第四课测试可使用 `.\mvnw.cmd "-Dtest=dev.learnagent.s04.*Test" test`，无需 API 密钥。

## 编码风格与命名

文件读写默认使用 UTF-8。新增 Java 代码使用四个空格缩进；类名采用 `PascalCase`，方法和变量采用 `camelCase`，常量采用 `UPPER_SNAKE_CASE`。保持包名全小写。引入格式化或静态检查工具时，提交配置，并让本地命令与持续集成使用同一套规则。

## 测试指南

使用 JUnit 5，版本固定在 `pom.xml`，暂不设覆盖率门槛。增加功能时，为关键行为和错误路径编写测试；测试类按 `*Test.java` 命名并放入 `src/test/java`。HTTP 协议测试使用本地模拟服务，真实服务商验证单独记录。提交前运行相关测试，在拉取请求中记录结果。

## 提交与拉取请求

当前目录没有 Git 历史，因此没有可归纳的提交格式。建立仓库后，建议使用简短、可检索的提交主题，例如 `feat: add agent runner` 或 `fix: handle timeout`，一次提交聚焦一项改动。拉取请求应说明目的、主要改动和验证结果；关联适用的议题。涉及界面时附截图，涉及配置时列出新增配置项及默认值。



基本java开发规则


1、使用jdk 17语法

2、工具类优先使用hutool

3、json使用fastjson2
