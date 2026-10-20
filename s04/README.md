# S04：四类 Hook

复制 S03 完整能力，加入 UserPromptSubmit、PreToolUse、PostToolUse、Stop。自定义 Java Hook 注册一次即可收到四类事件。

## 包结构

根包只保留默认入口 `HooksMain`，其余按职责分包：

```text
dev.learnagent.s04
├── HooksMain                 # 默认入口，组装完整 S04
├── app                       # 前两课保留的工具/权限组装入口
├── config                    # AgentConfig
├── loop                      # ChatLoop：输入、历史与模型/工具循环
├── model                     # ChatMessage、ModelClient、OpenAiClient
├── tool                      # 工具接口、调用数据、注册表和 Shell
│   └── file                  # 四个文件工具、WorkspaceFiles、ToolArguments
├── permission                # 权限接口、策略与逐次确认
└── hook                      # AgentHook、AgentHooks、LoggingHook
```

从 [HooksMain](src/main/java/dev/learnagent/s04/HooksMain.java) 看组装；学习 Hook 时重点读 [hook](src/main/java/dev/learnagent/s04/hook)，再看 [loop/ChatLoop](src/main/java/dev/learnagent/s04/loop/ChatLoop.java) 和 [tool/ToolRegistry](src/main/java/dev/learnagent/s04/tool/ToolRegistry.java) 的触发点。对应测试按相同子包组织。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `hook.AgentHook` | 新增四事件接口 |
| `hook.AgentHooks` | 新增有序分派、短路、异常隔离和消息快照 |
| `hook.LoggingHook` | 四类事件日志示例 |
| `loop.ChatLoop` | 新增提示词记录前和最终回复后事件 |
| `tool.ToolRegistry / app.ToolUseMain.createTools` | 新增工具前后事件和共享事件链注入 |
| `HooksMain` | 默认入口，可注册自定义 Hook |

生产源码位于 [src/main/java/dev/learnagent/s04](src/main/java/dev/learnagent/s04)，测试位于 [src/test/java/dev/learnagent/s04](src/test/java/dev/learnagent/s04)。没有其他课的 Java 依赖；下一课复制本课源码后修改，不影响本课。

`loop.SingleToolLoopTest` 验证单工具，`loop.ChatLoopTest` 验证多工具。`app.ToolUseMain` 和 `app.PermissionMain` 保留前一课入口/组装逻辑用于对照，Maven 默认启动根包的 `HooksMain`。

## 单独运行

在本目录内：

```powershell
..\mvnw.cmd test
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

默认入口 `HooksMain`，不用指定 exec.mainClass。也可用本机 Maven：`mvn test` / `mvn -q compile exec:java`。父 POM 通过相对路径读取，不需要安装其他课程。

将本目录 `config.example.properties` 复制为 `config.properties` 并填写。或在仓库根运行 `.\mvnw.cmd -pl s04 -q compile exec:java`，使用根目录已有配置。配置与相对工作目录均以进程启动目录为准。

机制与练习见 [课程讲解](../docs/lessons/s04-hooks.md)。
