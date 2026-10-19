# Java Agent 实战

当前实现第一课的第一步：OpenAI 兼容接口的纯文本对话循环。每次输入调用一次模型，成功的问答保留在当前进程的历史中；输入 `exit`、`quit` 或遇到输入结束时退出。

使用 JDK 17、Maven、Hutool 和 fastjson2。工具调用、Shell 执行及自动任务循环留待后续课程。

## 构建与启动

在项目根目录编辑 `config.properties`（UTF-8 编码），填写服务商地址、模型和密钥。新检出项目时，先复制 `config.example.properties` 为 `config.properties`。

```properties
openai.base-url=https://你的服务商地址/v1
openai.model=你的模型名称
openai.api-key=你的密钥
```

值不要加引号。保存后在项目根目录运行：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package

.\mvnw.cmd -q compile exec:java
```

也可直接使用已安装的 Maven，例如 `mvn test`、`mvn package`、`mvn -q compile exec:java`。Wrapper 固定 Maven 3.9.3，首次运行需联网下载 Maven 和依赖。`package` 生成普通 JAR，运行时通过 `exec:java` 加载依赖。

| 配置项 | 说明 | 默认值 |
| --- | --- | --- |
| `openai.base-url` | 服务商的 API 基础地址，保留其要求的路径前缀；程序追加 `/chat/completions` | 省略时使用 `https://api.openai.com/v1` |
| `openai.model` | 服务商支持的模型 ID | 必填 |
| `openai.api-key` | API 密钥，仅在本地设置 | 必填 |

基础地址不要填写完整的 `/chat/completions` 地址。程序启动时读取当前工作目录的 `config.properties`，修改后需重启；在 IDE 中运行时将工作目录设为项目根目录。API 配置只从文件读取。`config.properties` 已加入 Git 忽略规则，不打入 JAR，示例文件只保留空值。

请求采用非流式 Chat Completions 协议。连接和单次请求超时均为 60 秒；失败后显示错误，本轮问答不进入历史，也不会自动重试。输入下一条消息即可继续。历史保存在内存，重启清空，当前未做截断或摘要；长对话达到服务商上下文限制时可退出重启。

## 从哪里读代码

- `src/main/java/dev/learnagent/s01/AgentLoopMain.java`：加载配置，组装客户端和终端输入输出。
- `src/main/java/dev/learnagent/s01/AgentConfig.java`：以 UTF-8 读取 `config.properties`，校验必填项。
- `src/main/java/dev/learnagent/s01/ChatLoop.java`：本课重点，负责循环、历史和退出。
- `src/main/java/dev/learnagent/s01/ModelClient.java`：模型调用入口，便于离线验证循环。
- `src/main/java/dev/learnagent/s01/OpenAiClient.java`：构造 HTTP 请求、解析文本回答。
- `src/main/java/dev/learnagent/s01/ChatMessage.java`：消息的角色与内容。
- `src/test/java/dev/learnagent/s01/`：循环行为和本地 HTTP 模拟服务测试，不调用真实模型。

阅读[第一步讲解](docs/lessons/s01-agent-loop.md)了解消息历史如何工作；后续规划见[实战方案](docs/JAVA_PRACTICE_SPEC.md)。

## 验证边界

自动测试使用模拟模型与本地 HTTP 服务，验证协议和程序行为。真实服务商调用尚未验证，需要填写本地 `config.properties` 后运行。
