# Repository Guidelines

## 项目结构与模块组织

四个独立 Maven 子工程，根 POM 仅聚合构建并统一版本：

- `s01`：最小文本对话，无工具；入口 `dev.learnagent.s01.AgentLoopMain`。
- `s02`：基于 S01 增加单工具、多工具分派、Shell 和文件工具；入口 `dev.learnagent.s02.ToolUseMain`。
- `s03`：基于 S02 增加权限策略和逐次确认；入口 `dev.learnagent.s03.PermissionMain`。
- `s04`：基于 S03 增加四类 Hook；入口 `dev.learnagent.s04.HooksMain`。

每课有完整源码、测试、POM、配置示例与 README；类全部在本模块的 `dev.learnagent.s0N` 包及其子包。后续课复制前一课阶段源码后修改扩展，不通过跨模块 Java 依赖或 common 工程隐藏实现。用户已明确用教学快照替代旧版“共用一份循环、不复制”的要求。

生产代码和测试分别放在各模块内部的 `src/main/java` 与 `src/test/java`，包路径对应。扩展课程前读 `docs/JAVA_PRACTICE_SPEC.md`，更新根 README 和模块 README 的职责、入口与增量。不要提前将工具、权限或 Hook 加入 S01。

S04 根包只保留默认入口 `HooksMain`；按职责拆为 `app`、`config`、`loop`、`model`、`tool`、`tool.file`、`permission`、`hook`。测试使用对应子包，不将新类重新堆回根包。Hook 接口与实现位于 `s04.hook`：`AgentHook`、`AgentHooks`、`LoggingHook`。事件为提示词记录前、工具调用前后、任务完成后。前置放行仍需权限检查；后置异常不覆盖结果；超限、失败、中断和退出不触发 Stop。

S04 命令 Hook 固定加载本子项目的 `s04/.agents/hook.json`，不受进程工作目录影响，不读取或回退到父工程 Hook 配置；`config.S04ProjectDirectory` 从本模块编译产物定位子项目。`HookConfig`、`CommandHook`、`HookCommandRunner` 均放在 `s04.hook`。配置错误阻止启动；命令在 S04 子项目目录运行，通过 UTF-8 stdin 接收 JSON，不将提示词或工具参数拼接到命令中。前置非零退出或执行失败拒绝，后置失败只告警；命令有独立超时和输出上限。Java Hook 属于 Agent 内置行为，JSON Hook 属于用户扩展；所有 HooksMain.createLoop 重载均保留两者，顺序为内置日志、追加的 Java Hook、用户 JSON Hook。Java 列表只追加，不替换默认注册。

用户 JSON Hook 必须经过独立的 HookTrust 启用确认，不能复用或绕过 S03 工具审批。首次或内容变化时支持 y/yes 记忆授权、once 单次会话、其余及 EOF 不启用；拒绝仍保留 Java Hook。HookTrustStore 默认写入用户目录 `.java-agent/hook-trust/`，项目 JSON 不得自授信任。HookTrustSnapshot 校验配置、字面量脚本和 trust_files 的内容，执行前检测变化；失效后前置拦截、后置告警，重启再确认。测试必须使用临时信任存储，不写真实用户记录。内容摘要不是脚本安全证明或系统沙箱。

## 构建与测试

JDK 17、Maven；工具类优先 Hutool，JSON 使用 fastjson2。JUnit 5 版本固定在父 POM。

根目录 `mvn test` / `mvn package` 构建全部；`mvn -pl s02 test` 只测第二课；`mvn -pl s04 -q compile exec:java` 启动第四课。Windows 使用 `./mvnw.cmd` 替代 `mvn`。子工程内可用 `mvn test` 或 `../mvnw.cmd test`，不需先安装其他课程。

程序从进程工作目录读 `config.properties`，相对工具目录也以此为准。根目录启动共用根配置，子工程内启动用自己的配置。所有 config.properties 与 target/ 均忽略，不提交密钥。

自动测试无需 API 密钥，HTTP 协议使用本地模拟服务；真实服务商验证单独记录。危险命令仅作为权限分类字符串；真实执行使用临时目录和无副作用命令。各模块保留完整阶段测试，不能因为其他模块测试过而删除本模块关键回归。

## 编码规范

UTF-8；四空格缩进；类名 PascalCase，方法/变量 camelCase，常量 UPPER_SNAKE_CASE，包名小写。测试类命名 `*Test.java`。增加功能覆盖关键行为和错误路径。结构或父 POM 改动需全量构建及独立入口验证。

## 提交

主题简短、可检索，一次聚焦一项改动，例如 `feat: add hooks`、`refactor: split lesson modules`。记录目的、模块差异和验证结果，不夹带用户其他学习笔记与素材的未提交改动。旧课次记录只作历史，当前范围与命令以 README 和实战规范为准。
