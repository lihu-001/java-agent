# S01：最小文本对话 Loop

只实现输入、模型请求、历史、退出及错误恢复。没有工具、权限和 Hook。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `AgentLoopMain` | 加载配置和终端，启动循环 |
| `AgentConfig` | 只读取模型地址、模型名、密钥 |
| `ChatMessage` | 只有 role / content |
| `ModelClient` | complete(messages) 返回文本 |
| `OpenAiClient` | 纯文本协议，不发送工具字段 |
| `ChatLoop` | 每条有效输入请求一次模型 |

生产源码位于 [src/main/java/dev/learnagent/s01](src/main/java/dev/learnagent/s01)，测试位于 [src/test/java/dev/learnagent/s01](src/test/java/dev/learnagent/s01)。没有其他课的 Java 依赖；下一课复制本课源码后修改，不影响本课。

## 单独运行

在本目录内：

```powershell
..\mvnw.cmd test
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

默认入口 `AgentLoopMain`，不用指定 exec.mainClass。也可用本机 Maven：`mvn test` / `mvn -q compile exec:java`。父 POM 通过相对路径读取，不需要安装其他课程。

将本目录 `config.example.properties` 复制为 `config.properties` 并填写。或在仓库根运行 `.\mvnw.cmd -pl s01 -q compile exec:java`，使用根目录已有配置。配置与相对工作目录均以进程启动目录为准。

机制与练习见 [课程讲解](../docs/lessons/s01-agent-loop.md)。
