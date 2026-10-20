# S04：四个常用 Hook

本课基于 S03 增量实现四类事件：用户提示词记录前、工具调用前、工具调用后、任务完成后。入口为 `dev.learnagent.s04.HooksMain`，沿用已有配置和五个工具。

## 1. 四个事件及其时机

| 事件 | Java 方法 | 触发时机 | 回调能做什么 |
| --- | --- | --- | --- |
| `UserPromptSubmit` | `userPromptSubmit(prompt, history)` | 有效用户提示词写入历史、请求模型之前 | 观察提示词和此前历史，或拒绝本次提交 |
| `PreToolUse` | `preToolUse(call)` | 找到工具后，权限检查和实际执行之前 | 观察参数，或阻止本次调用 |
| `PostToolUse` | `postToolUse(call, result, elapsedNanos)` | 工具产生结果后、结果回传模型之前 | 观察结果及工具执行耗时 |
| `Stop` | `stop(turn)` | 本轮最终回复保存并输出之后 | 查看本轮消息，执行完成后的自定义处理 |

这里的“任务完成”指模型返回不含工具调用的最终回复，表示本轮循环结束，不是独立验证了用户目标已经达成。直接文字回答也触发 `Stop`；发生工具拒绝后，模型正常给出解释也会触发。每轮正常结束只触发一次。

空行、退出命令、EOF 不触发提交或完成事件；权限确认输入 `y/n` 由 S03 消费，不属于新的用户提示词。达到模型调用上限、请求失败、超时或中断不触发 `Stop`。

```text
有效用户输入
  → UserPromptSubmit
  → 将用户消息加入本轮历史
  → 请求模型
      ├─ 有工具调用
      │   → PreToolUse
      │   → S03 权限检查
      │   → 工具执行
      │   → PostToolUse
      │   → 按调用 ID 记录结果，再请求模型
      └─ 无工具调用
          → 保存并输出最终回复
          → Stop
          → 等待下一次输入
```

模型仍只看见五个工具。Hook 由程序自动触发，无需模型主动调用。

## 2. 类的位置和职责

Hook 相关类全部在 `src/main/java/dev/learnagent/s04/` 下：

- `AgentHook`：四个事件的接口；每个方法都有默认空实现，只需覆盖关心的事件。
- `AgentHooks`：保存有序回调列表，统一处理前置短路、不可变消息快照和后置异常隔离。
- `LoggingHook`：四类事件的默认日志示例。
- `HooksMain`：组装模型客户端、S03 权限和同一条 Hook 链；提供自定义注册入口。

`s01.ChatLoop` 在用户消息记录前和正常结束后触发事件；`s01.ToolRegistry` 触发工具前后事件。两个触发点共用同一个 `AgentHooks` 实例，没有复制对话循环。S01—S03 的旧构造方法默认使用空事件链，运行入口继续可用。

默认仅注册日志示例，已移除之前的 `ProtectedPathHook`。`protected/` 不再具有 S04 特殊规则，文件写入仍遵循 S03 逐次权限确认和 S02 工作区边界。

## 3. 如何自定义 Hook

自定义 Java 类实现 `s04.AgentHook`，覆盖任意方法即可。例如，阻止空白字符以外仅含“取消”的提交，并在每轮完成后处理最终回复：

```java
package dev.learnagent.s04;

import dev.learnagent.s01.ChatMessage;
import java.util.List;

public final class MyHook implements AgentHook {
    @Override
    public String userPromptSubmit(String prompt, List<ChatMessage> history) {
        return "取消".equals(prompt.strip()) ? "本次提交已取消。" : null;
    }

    @Override
    public void stop(List<ChatMessage> turn) {
        ChatMessage answer = turn.get(turn.size() - 1);
        System.out.println("自定义完成处理> " + answer.content());
    }
}
```

在 `HooksMain` 的默认 `createLoop` 中，将注册列表改为：

```java
return createLoop(config, client, input, output,
        List.of(new LoggingHook(output), new MyHook()));
```

或者在自己的入口中调用：

```java
HooksMain.createLoop(config, client, input, output,
        List.of(new LoggingHook(output), new MyHook())).run(input, output);
```

这里的 `config`、`client`、`input`、`output` 按原入口初始化。只注册一次，四类事件都能收到。同一事件有多个回调时，始终按列表顺序执行；前置拒绝会停止后续前置回调。自定义列表会替换默认列表，需要日志时显式保留 `LoggingHook`。

`history` 是提交前的历史快照，不包含当前提示词。`turn` 是当前轮次的用户消息、工具往返和最终回复，不包含系统消息及此前轮次。快照和消息不可修改，避免 Hook 破坏聊天协议或在检查后改写执行参数。

本课范围是 Java 回调注册，不包含配置文件加载 Hook、外部脚本执行、异步调度或自动重试。

## 4. 拒绝、异常与中断

两个前置方法返回 `null` 表示继续，返回任何非 `null` 字符串（包括空串）表示拒绝。前置 IO / 运行时异常也拒绝本次事件，只报告异常类型。

- `UserPromptSubmit` 拒绝：本次提示词不进入历史，不请求模型，不触发完成事件，继续等待下一条输入。
- `PreToolUse` 拒绝：不进入权限确认，不执行工具，不触发后置事件；仍按原调用 ID 回传拒绝结果。
- 前置钩子放行后，S03 权限检查仍可拒绝；Hook 不能替代权限许可。
- 工具正常返回或错误转换为结果后，都触发 `PostToolUse`。它表示产生了结果，不保证成功。
- `PostToolUse` / `Stop` 抛出 IO 或运行时异常：输出警告，继续其他后置回调，保留工具结果或最终回复，不重新请求模型。
- 前置、权限或工具阶段发生 `InterruptedException`：传播到原循环，恢复中断标记并退出，不伪造任务完成事件。

未知工具直接返回错误，不触发工具事件。调用超限时由循环补齐未执行结果，也不触发工具事件。工具非预期运行时异常会提示“操作可能已部分完成”，异常与中断均不承诺撤销已发生的操作。

回调按序同步执行，未设置独立超时；只注册可信且能快速完成的 Java 逻辑。Shell 原有的超时只控制 Shell，不控制 Hook。

## 5. 运行与观察

```powershell
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s04.HooksMain"
```

输入“你好”，如果模型直接回答，日志顺序为：

```text
钩子 UserPromptSubmit> ...
助手> ...
钩子 Stop> 本轮回复完成，工具结果 0 条
```

再输入：

```text
只用 write_file 在 s04-demo/note.txt 写入“第一版”，然后用 read_file 读回。若写入被拒绝就停止。
```

权限确认时输入 `y`。若模型提出指定调用，预期顺序为：

```text
UserPromptSubmit
PreToolUse(write_file) → 权限确认 → 实际写入 → PostToolUse(write_file)
PreToolUse(read_file) → 实际读取 → PostToolUse(read_file)
最终回复 → Stop
```

`LoggingHook` 的提交日志显示提示词字符数和记录前历史条数；工具日志显示调用 ID、仅含执行阶段的耗时和结果字符数；完成日志统计本轮 `tool` 消息数，包含拒绝或错误结果。日志输出到终端，不进入模型历史。

测试自定义拒绝时可使用上节 `MyHook` 并输入“取消”；应该只看到拒绝，不出现模型回答或 `Stop`。结束时在 `你>` 输入 `exit`，退出本身不触发 `Stop`。

上述步骤是手动验收说明，不表示已经执行真实模型验收。

## 6. 自动验证

```powershell
.\mvnw.cmd clean test "-Dtest=dev.learnagent.s04.*Test"
.\mvnw.cmd package
```

| 测试类 | 数量 | 验证重点 |
| --- | --- | --- |
| `HookRegistryTest` | 16 | 工具事件顺序、短路、权限、异常及中断 |
| `HooksLoopTest` | 4 | 混合调用 ID、拒绝不写入、逐次确认、调用上限及后置异常 |
| `LifecycleHooksTest` | 16 | 四事件全链路、记录前时机、消息快照、完成事件边界及自定义注册 |

2026-10-20：S04 共 36 项全部通过；全量打包共 190 项，189 项通过、1 项因 Windows 符号链接权限跳过，无失败或错误。测试使用模拟模型、临时文件和原有本地 HTTP 协议测试。S04 尚未请求真实模型服务验收。
