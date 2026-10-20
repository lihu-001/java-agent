# S02：工具与多工具

复制 S01 基础代码，加入 Shell 和四个文件工具；工具结果回传模型后可继续请求。没有权限或 Hook 类。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `ChatMessage / ModelClient / OpenAiClient` | 扩展 tools / tool_calls 协议 |
| `AgentConfig` | 新增工作目录、请求上限、超时和输出限制 |
| `AgentTool / ToolDefinition / ToolCall` | 新增工具接口和调用数据 |
| `ToolRegistry` | 新增按名称注册与分派 |
| `ShellTool / 文件工具 / WorkspaceFiles` | 新增真实执行和工作区检查 |
| `ChatLoop` | 从单次文本请求扩展为多次工具往返 |
| `ToolUseMain` | 默认入口，组装五个工具 |

生产源码位于 [src/main/java/dev/learnagent/s02](src/main/java/dev/learnagent/s02)，测试位于 [src/test/java/dev/learnagent/s02](src/test/java/dev/learnagent/s02)。没有其他课的 Java 依赖；下一课复制本课源码后修改，不影响本课。

`SingleToolLoopTest` 验证单工具，`ChatLoopTest` 验证多工具。后续模块保留前一课入口/组装类用于对照，Maven 默认启动本课入口。

## 单独运行

在本目录内：

```powershell
..\mvnw.cmd test
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

默认入口 `ToolUseMain`，不用指定 exec.mainClass。也可用本机 Maven：`mvn test` / `mvn -q compile exec:java`。父 POM 通过相对路径读取，不需要安装其他课程。

将本目录 `config.example.properties` 复制为 `config.properties` 并填写。或在仓库根运行 `.\mvnw.cmd -pl s02 -q compile exec:java`，使用根目录已有配置。配置与相对工作目录均以进程启动目录为准。

机制与练习见 [课程讲解](../docs/lessons/s02-tool-use.md)。
