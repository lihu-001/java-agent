# 用 Java 创建 Agent：先跑通对话循环

创建 Agent 的第一步，先让程序能够持续接收输入、请求模型，并带着历史继续对话。本阶段采用 Java 17 和 OpenAI 兼容接口，只实现文本对话，工具调用后续再加。

核心流程很简单：

```text
读取输入 → 携带历史请求模型 → 输出回答 → 更新历史 → 等待下一次输入
```

**多轮对话的关键是主动传递历史。** 当前实现每次调用 Chat Completions，都会把此前成功的问答放进 `messages`。例如，先说“我叫小李”，再问“我叫什么？”，第二次请求需要包含：

```text
system：你是一个友好的助手，请用中文回答。
user：我叫小李
assistant：你好，小李
user：我叫什么？
```

程序用 `List<ChatMessage>` 保存这些消息，`ChatMessage` 只包含角色和内容：

```java
public record ChatMessage(String role, String content) {}
```

**历史只在请求成功后更新。** 如果先把用户输入写入历史，再调用模型，请求失败后就会留下没有回答的问题。这里选择先创建本轮请求副本，成功后再将完整问答作为新历史。

`ChatLoop.run` 中负责这一过程的核心语句是：

```java
List<ChatMessage> request = new ArrayList<>(history);
request.add(new ChatMessage("user", line));

String answer = client.complete(List.copyOf(request));

request.add(new ChatMessage("assistant", answer));
history = request;
output.println("助手> " + answer);
```

`new ArrayList<>(history)` 创建独立列表，向 `request` 添加消息不会修改原来的 `history`。这里是浅拷贝，但消息由不可变的 record 和 String 组成，足以满足当前需求。

`List.copyOf(request)` 将不可修改的列表交给模型客户端，避免客户端改动本轮消息。请求成功后，追加助手回答，再执行 `history = request`。这样，用户消息只需要添加一次。

如果模型调用抛出异常，就不会执行后面的追加和赋值。外围异常处理会提示失败，下一次输入仍从原来的历史开始。请求超时为 60 秒，不自动重试；线程中断则结束对话。

循环本身只负责输入、历史和输出。`OpenAiClient` 使用 JDK HttpClient 发送请求，用 fastjson2 序列化消息并读取回答。API 地址、模型和密钥从项目根目录的 `config.properties` 读取。

运行时，空输入直接跳过，输入 `exit`、`quit` 或遇到输入结束时退出。历史保存在内存中，重启后清空。当前已有测试验证多轮消息顺序、失败后恢复、退出和 HTTP 超时。

这一阶段需要掌握的是：**每轮请求使用历史副本，成功后保存完整问答，失败时保留原历史。** 后续加入工具调用时，再扩展模型回复之后的处理流程。
