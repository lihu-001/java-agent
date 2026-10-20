# S04：四个常用 Hook

本课在独立 s04 子工程中基于 S03 阶段源码增量实现四类事件：用户提示词记录前、工具调用前、工具调用后、任务完成后。入口为 `dev.learnagent.s04.HooksMain`，沿用已有配置和五个工具。用户在 `.agents/hook.json` 中配置命令，脚本从标准输入读取事件 JSON，无需修改 Java 源码。

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

Hook 接口和实现位于 `s04/src/main/java/dev/learnagent/s04/hook/`，默认入口仍在根包：

- `AgentHook`：四个事件的接口；每个方法都有默认空实现，只需覆盖关心的事件。
- `AgentHooks`：保存有序回调列表，统一处理前置短路、不可变消息快照和后置异常隔离。
- `LoggingHook`：四类事件的默认日志示例。
- `HookConfig`：启动时读取 JSON，校验配置并创建命令回调。
- `CommandHook`：按事件和工具名称匹配，生成输入 JSON，解释退出码。
- `HookCommandRunner`：调用系统 Shell，传入 stdin，限制超时和输出，处理中断与进程清理。
- `HookTrust`：显示用户命令并确认是否启用；拒绝时仍保留内置 Java Hook。
- `HookTrustStore`：按规范化项目路径，在用户目录保存已批准内容的摘要。
- `HookTrustSnapshot`：记录配置和脚本文件内容，每次命令执行前检查变更。
- `HooksMain`：组装模型客户端、S03 权限和同一条 Hook 链，默认注册日志及配置命令；保留 Java 注册入口。

`s04.loop.ChatLoop` 在用户消息记录前和正常结束后触发事件；`s04.tool.ToolRegistry` 触发工具前后事件。两个触发点共用同一个 `AgentHooks` 实例。本课修改自己的循环，不影响前面课程。S01—S03 有各自源码，不包含 Hook 类；本模块基础构造方法默认使用空事件链。

Java Hook 属于 Agent 内置行为，JSON Hook 属于用户扩展，后者经过启用授权后与前者同时注册。执行顺序为内置日志、Agent 追加的 Java Hook、用户 JSON 命令。JSON 缺失、为空或用户拒绝启用时不会关闭 Java Hook。`protected/` 没有 S04 特殊规则，文件写入仍遵循 S03 逐次权限确认和 S02 工作区边界。

## 3. JSON 配置命令 Hook

默认位置固定为 **S04 子项目下的 `.agents/hook.json`**：

- 从仓库根运行 `.\mvnw.cmd -pl s04 ...`、进入 `s04` 运行或在 IDEA 运行，均读取 `s04/.agents/hook.json`。
- `S04ProjectDirectory` 根据自身编译产物定位子项目，支持 Maven 的 `s04/target/classes`、`s04/target/*.jar` 和 IDEA 仓库内的 `out/production/<module>`；无法定位源码工程时明确报错。
- 不受进程工作目录或 `agent.work-dir` 影响，不读取、不回退到父工程的 Hook 配置；命令的工作目录固定为 S04 子项目。`config.properties` 和模型工具工作目录继续遵循原有规则。
- 启动时读取一次，修改后重启生效。文件不存在或 `{"hooks": {}}` 均表示没有命令 Hook。
- 配置无法读取、JSON 格式错误、未知事件或字段、非法参数均阻止启动，不会静默忽略检查。

### 用户 Hook 的信任确认

配置解析后先显示事件、匹配器、命令、工作目录和跟踪文件，不执行任何命令。首次启用或内容发生变化时，询问：

```text
启用用户 Hook？[y=信任并记住 / once=仅本次 / N=不启用]
```

- `y/yes`：信任当前项目的这一份配置与跟踪文件，保存记忆授权。
- `once`：只启用本次会话，不保存。
- 其他输入或 EOF：不注册用户命令 Hook，Agent 内置日志、Java 回调和聊天仍可继续。

授权记录位于 `~/.java-agent/hook-trust/<项目路径摘要>.json`，记录规范化项目路径与 SHA-256 内容摘要，不保存脚本正文。项目配置没有 `trusted` 字段，也不能指定信任存储位置。记录损坏、缺失或读取失败都不会自动授权；保存失败则明确提示仅本次启用。删除程序打印的记录文件，下次启动会重新询问。

确认答案与聊天共享输入流，但由信任模块消费，不进入模型历史。该授权允许用户命令在后续事件中自动运行，不代替 S03 的文件写入、Shell 等工具审批。自动化输入也必须明确提供确认答案；没有输入时不会默认批准。

配置文件本身以及命令中可识别的字面量脚本路径参与内容校验，支持 `.py`、`.ps1`、`.sh`、`.bash`、`.js`、`.mjs`、`.cjs`、`.ts`、`.rb`、`.pl`、`.bat`、`.cmd`，支持单/双引号路径。脚本文件不仅比较路径或修改时间，还比较实际内容与符号链接目标路径。配置变化即使只是空白变化也需重新授权。确认期间发生变化会取消启用。

每个命令实际运行前重新检查快照。发现变化或无法读取时，本会话的用户 Hook 信任失效；前置拒绝操作，后置告警并保留结果，重启后重新确认。这里不在事件回调中再次询问，以免后置事件或脚本修改意外消费下一条聊天输入。

动态拼接的路径、解释器、导入模块和间接执行的脚本不自动跟踪。对已知依赖，用 `trust_files` 显式列出普通文件，例如：

```json
{
  "type": "command",
  "command": "python examples/hooks/example_hook.py",
  "trust_files": ["examples/hooks/example_hook.py", "examples/hooks/policy.json"],
  "timeout": 10
}
```

`trust_files` 相对路径以 S04 为基准，也可指定绝对路径；列出的文件必须存在且可读。文件缺失或无法校验时，不启用用户命令并给出提示，仍可使用内置 Java Hook。内容跟踪只表示“与用户批准的内容一致”，不证明命令安全，不提供文件/网络隔离，也无法消除校验与执行之间的并发替换风险。

最小示例：每次模型尝试调用 `shell`，先运行配置命令。下面的命令输出原因并返回 `2`，因此工具被拒绝：

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "shell",
        "hooks": [
          {
            "type": "command",
            "command": "echo shell-disabled; exit 2",
            "timeout": 10
          }
        ]
      }
    ]
  }
}
```

| 字段 | 约定 |
| --- | --- |
| `hooks` | 顶层必填对象；键仅支持四个事件名，值为分组数组 |
| 分组 `matcher` | 仅工具前后事件支持；省略或 `"*"` 匹配全部，其他值为 Java 正则，匹配完整工具名，如 `"write_file|edit_file"` |
| 分组 `hooks` | 必填命令数组；同一事件按分组顺序、组内命令顺序同步执行 |
| `type` | 必须为 `"command"` |
| `command` | 必填非空字符串；Windows 使用 PowerShell，Unix 使用 `/bin/sh` |
| `timeout` | 可选整数秒，默认 30，范围 1—300；每条命令分别计时，包括输出读取 |
| `trust_files` | 可选非空路径字符串数组；补充参与信任摘要校验的脚本或依赖文件 |

`UserPromptSubmit` 和 `Stop` 的分组不填写 `matcher`。空事件数组或空命令数组不执行命令。命令不做模板替换：工具参数、提示词等数据只通过 stdin 传递，不能写成 `${tool_input}` 期待自动展开。

### 命令输入和输出

每次执行给 stdin 一个 UTF-8 JSON 对象，随后 EOF。所有事件都有 `hook_event_name` 和 `cwd`：

| 事件 | 额外输入字段 |
| --- | --- |
| `UserPromptSubmit` | `prompt`、`history`：当前提示词及记录前历史，历史不含当前提示词 |
| `PreToolUse` | `tool_name`、`tool_call_id`、`tool_arguments`、`tool_input` |
| `PostToolUse` | 同工具前置，加 `tool_response` 和 `elapsed_ms`，耗时仅计算工具执行 |
| `Stop` | `turn`：本轮消息数组，不含此前历史 |

`tool_arguments` 是模型原始参数字符串，`tool_input` 是解析后的 JSON；参数无法解析时 `tool_input` 保留原始字符串，由工具原有校验处理。消息对象含 `role`、`content`、`tool_call_id`、`tool_calls`；调用对象含 `id`、`name`、`arguments`。无值字段可能省略。

例如 `PreToolUse` 的 stdin：

```json
{
  "hook_event_name": "PreToolUse",
  "cwd": "C:\\work\\java-agent\\s04",
  "tool_name": "shell",
  "tool_call_id": "call_1",
  "tool_arguments": "{\"command\":\"Get-ChildItem\"}",
  "tool_input": { "command": "Get-ChildItem" }
}
```

| 命令结果 | 两个前置事件 | 两个后置事件 |
| --- | --- | --- |
| 退出码 `0` | 放行，继续下一个 Hook，再做原有权限检查 | 成功，继续后续回调 |
| 退出码 `2` | 拦截，输出作为拒绝原因 | 告警，继续回调，保留原结果 |
| 其他非零退出码 | 视为执行失败，拦截本次操作 | 告警，继续回调，保留原结果 |
| 启动失败、超时或读取失败 | 视为检查失败，拦截 | 告警，保留原结果 |

stdout 和 stderr 合并，最多保留 12000 字符，超出后继续消费管道并添加截断标记。成功命令的输出只显示在终端，不进入模型历史；前置工具拒绝的原因会随原调用 ID 回传模型。输出是普通文本，不解析 JSON 控制指令，不能借此改写参数、结果或让模型续跑。

命令经独立信任授权后，以当前用户权限执行，不经过模型工具的逐次确认。stdin 使用临时文件重定向，执行结束后清理；命令超时或中断时尽力终止进程树，这不是系统沙箱。Java 运行本身不要求 Python，只有选用 Python 脚本时需要安装对应解释器。

### 完整脚本示例

四事件配置见 [S04 示例](../../s04/.agents/hook.example.json)，脚本是 [example_hook.py](../../s04/examples/hooks/example_hook.py)。将示例内容写入 `s04/.agents/hook.json`，重启后选择 `once` 或 `y`，示例会：

- 对内容为“取消”的提示词返回 `2`，无需请求模型。
- 拦截 Shell 工具调用。
- 对实际执行的工具和正常完成的轮次输出日志。

无论在哪里启动，命令都是 `python examples/hooks/example_hook.py`，因为 Hook 命令固定在 S04 目录运行。示例需要 Python 3；可换成你自己的 PowerShell、Shell 或其他程序。

### Agent 内置 Java Hook

Agent 开发者可以用 Java 类实现 `s04.hook.AgentHook`，覆盖任意方法；使用者通过 JSON 配置自己的命令。下面演示增加一个 Agent 内置 Java Hook，新建 `s04/src/main/java/dev/learnagent/s04/hook/MyHook.java`，阻止内容为“取消”的提交，并在每轮完成后处理最终回复：

```java
package dev.learnagent.s04.hook;

import dev.learnagent.s04.model.ChatMessage;
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

需要直接使用 Java 回调时，在自己的入口中导入相关类，然后调用：

```java
HooksMain.createLoop(config, client, input, output,
        List.of(new MyHook())).run(input, output);
```

这里的 `config`、`client`、`input`、`output` 按原入口初始化。传入列表追加 Agent 的 Java Hook，仍保留默认日志和用户 JSON 配置的独立信任流程，无需手动创建 LoggingHook 或调用配置加载器。获准的用户 Hook 与内置回调共享同一条链，每次事件按“内置日志 → 列表中的 Java Hook → 用户 JSON Hook”执行。前置任一拒绝会停止后续前置回调，后置异常不阻止其他回调。测试或嵌入时可使用 `createLoop(..., projectDirectory, agentCallbacks)` 显式指定项目目录，隔离测试再传入临时 HookTrustStore。

`history` 是提交前的历史快照，不包含当前提示词。`turn` 是当前轮次的用户消息、工具往返和最终回复，不包含系统消息及此前轮次。快照和消息不可修改，避免 Hook 破坏聊天协议或在检查后改写执行参数。

本课支持 Java 回调及 JSON 命令 Hook，不包含热加载、异步调度或自动重试。

## 4. 拒绝、异常与中断

两个前置方法返回 `null` 表示继续，返回任何非 `null` 字符串（包括空串）表示拒绝。前置 IO / 运行时异常也拒绝本次事件，只报告异常类型。

- `UserPromptSubmit` 拒绝：本次提示词不进入历史，不请求模型，不触发完成事件，继续等待下一条输入。
- `PreToolUse` 拒绝：不进入权限确认，不执行工具，不触发后置事件；仍按原调用 ID 回传拒绝结果。
- 前置钩子放行后，S03 权限检查仍可拒绝；Hook 不能替代权限许可。
- 工具正常返回或错误转换为结果后，都触发 `PostToolUse`。它表示产生了结果，不保证成功。
- `PostToolUse` / `Stop` 抛出 IO 或运行时异常：输出警告，继续其他后置回调，保留工具结果或最终回复，不重新请求模型。
- 前置、权限或工具阶段发生 `InterruptedException`：传播到原循环，恢复中断标记并退出，不伪造任务完成事件。

未知工具直接返回错误，不触发工具事件。调用超限时由循环补齐未执行结果，也不触发工具事件。工具非预期运行时异常会提示“操作可能已部分完成”，异常与中断均不承诺撤销已发生的操作。

回调按序同步执行。JSON 命令使用各自的 `timeout`；直接注册的 Java 回调没有独立超时。Shell 工具原有的超时仍只控制模型调用的 Shell。前置命令中断传播到循环；后置命令中断保留线程中断标记并按后置异常处理，不覆盖已产生的结果。

## 5. 运行与观察

```powershell
.\mvnw.cmd -pl s04 -q compile exec:java
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
.\mvnw.cmd -pl s04 test "-Dtest=HookRegistryTest,HooksLoopTest,LifecycleHooksTest,HookConfigTest,CommandHookTest,HookTrustTest,S04ProjectDirectoryTest"
.\mvnw.cmd -pl s04 package
```

| 测试类 | 数量 | 验证重点 |
| --- | --- | --- |
| `HookRegistryTest` | 16 | 工具事件顺序、短路、权限、异常及中断 |
| `HooksLoopTest` | 4 | 混合调用 ID、拒绝不写入、逐次确认、调用上限及后置异常 |
| `LifecycleHooksTest` | 16 | 四事件全链路、记录前时机、消息快照、完成事件边界及自定义注册 |
| `HookConfigTest` | 35 | 缺省配置、BOM、格式和字段校验、读取失败、超时范围及 trust_files |
| `CommandHookTest` | 15 | Java 与 JSON 同时生效及顺序、双向前置拦截、后置隔离、四事件 JSON 输入、工作目录、权限、超时、中断和输出限制 |
| `S04ProjectDirectoryTest` | 6 | Maven/IDEA 编译产物定位、中文空格路径、不回退父工程配置、定位失败 |
| `HookTrustTest` | 19 | 首次确认、记忆/仅本次/拒绝、项目隔离、配置/脚本变更、存储故障、运行中失效、Java 回调与工具审批独立 |

JSON 命令 Hook、两类 Hook 组合、子项目定位和用户信任机制新增 75 项自动测试，使用本地命令、模拟模型和临时目录，不需要 API 密钥。信任测试使用临时存储，不读写真实用户的授权记录。构建结果见 [实战规范](../JAVA_PRACTICE_SPEC.md)。S04 尚未请求真实模型服务验收。
