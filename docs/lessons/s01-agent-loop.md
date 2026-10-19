# s01 第一步：先跑通对话循环

这一阶段只学习输入、模型调用和历史。原版 s01 包含工具执行，我们按当前约定拆开推进。

## 循环为什么需要历史

模型接口每次接收一份 `messages`，程序需要主动把之前的对话带上。第一次发送：

```json
[
  {"role": "system", "content": "你是一个友好的助手，请用中文回答。"},
  {"role": "user", "content": "我叫小李"}
]
```

收到“你好，小李”之后，再输入“我叫什么？”，第二次发送：

```json
[
  {"role": "system", "content": "你是一个友好的助手，请用中文回答。"},
  {"role": "user", "content": "我叫小李"},
  {"role": "assistant", "content": "你好，小李"},
  {"role": "user", "content": "我叫什么？"}
]
```

`ChatLoop.run` 对应的执行顺序是：

```text
初始化 system 消息
    ↓
读取一行输入 ←────────────────────┐
    ↓                            │
退出命令 / EOF → 结束             │
    ↓                            │
复制历史，追加本轮 user 消息       │
    ↓                            │
调用模型一次                     │
    ├─ 失败：提示错误 ─────────────┤
    └─ 成功：保存问答并输出 ───────┘
```

复制历史是为了让失败的请求不留下未完成的问答。`ModelClient` 将循环与 HTTP 分开，测试可以直接提供预设回答。`OpenAiClient` 使用 fastjson2 将 `model`、`messages`、`stream: false` 序列化成 JSON，再用 JDK HttpClient 发送。

## 动手观察

按根目录 README 配置并启动后，依次输入：

1. `我叫小李，请记住。`
2. `我刚才说自己叫什么？`
3. `exit`

在 `ChatLoop` 调用 `client.complete` 前设置断点，可观察第二次请求携带了第一轮问答。再运行 `mvn test`，查看失败后恢复和退出行为的自动验证。

模型回答具有不确定性；是否发送了正确历史由自动测试验证。当前没有工具定义、工具执行器或 Shell 访问，文件操作留到后续实现。

## 参考

- [OpenAI Chat Completions API](https://developers.openai.com/api/reference/resources/chat)：请求和响应协议。
- [fastjson2 官方说明](https://github.com/alibaba/fastjson2)：JSON 解析与序列化。
- [Maven Wrapper 官方说明](https://maven.apache.org/tools/wrapper/)：固定构建工具版本。
