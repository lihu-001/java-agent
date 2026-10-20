# S01：最小文本对话 Loop

源码位于 `s01/src/main/java/dev/learnagent/s01/`。本课不包含工具；Shell、文件操作和多工具循环从 S02 开始。

## 机制

```text
读一行输入 → 空行跳过 / exit、quit、EOF 退出
           → 复制历史，追加 user 消息
           → 请求模型一次
               ├─ 成功：追加 assistant，保存完整问答，显示回答
               └─ 失败：不保存本轮，报告错误，等待下一条输入
```

历史初始只有 system 提示词，保存在内存。每条有效输入只请求模型一次，没有自动重试或工具循环。线程中断会恢复中断标记并退出。

## 六个生产类

| 类 | 职责 |
| --- | --- |
| `AgentLoopMain` | 配置、终端、客户端与循环组装 |
| `AgentConfig` | UTF-8 配置，支持 BOM，密钥不出现在错误或 toString 中 |
| `ChatMessage` | role / content 两个字段 |
| `ModelClient` | `String complete(List<ChatMessage> messages)` |
| `OpenAiClient` | JDK HttpClient + fastjson2，非流式文本协议 |
| `ChatLoop` | 输入、请求、历史、回答与退出 |

HTTP 请求只有 model、messages、stream=false，不发送 tools 或 tool_choice。客户端拒绝工具调用、非文本、截断和过滤的响应；模型请求超时 60 秒。

## 运行与练习

```powershell
.\mvnw.cmd -pl s01 -q compile exec:java
```

从仓库根启动使用根 config.properties。依次输入“我叫小李”“我叫什么？”，观察历史保留；空行不请求模型，exit 退出。

本课不能执行命令或读取文件，文件问题只能得到文本回答。也可进入 s01 目录，使用本地配置运行 `..\mvnw.cmd -q compile exec:java`。

## 测试和下一课

```powershell
.\mvnw.cmd -pl s01 test
```

ChatLoopTest 验证成功历史、失败回滚、空输入、退出和中断；OpenAiClientTest 用本地 HTTP 服务验证中文、无工具字段、错误响应和超时；AgentConfigTest 验证配置和密钥遮蔽。

S02 在自己的模块内复制这些基础类，扩展消息协议和循环，再增加工具执行器。S01 不随下一课修改。
