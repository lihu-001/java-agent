# 用 Java 创建 Agent：先跑通对话循环

本阶段采用 Java 17 和 OpenAI 兼容接口

# S01、最小对话循环

创建 Agent 的第一步，先让程序能够持续接收输入、请求模型，并带着历史继续对话。

只实现文本对话，工具调用后续再加。

核心流程很简单：

![](assets/agent-chat.png)

**多轮对话的关键是主动传递历史。** 接口返回信息、历史对话信息也作为下次请求的参数。

`核心循环逻辑代码如下：`

```java

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", "你是一个友好的助手，请用中文回答。"));
        output.println("对话已启动，输入 exit 或 quit 退出。每行输入发送一次请求。");
        while (true) {
            //获取用户输入
            String line = input.readLine();          

            // 使用历史副本发起请求，成功后再用完整问答更新历史。
            List<ChatMessage> request = new ArrayList<>(history);
            request.add(new ChatMessage("user", line));
            //调用API接口
            String answer = client.complete(List.copyOf(request));
            //将返回信息添加到历史对话中
            request.add(new ChatMessage("assistant", answer));
            history = request;            
        }
  
```

这一阶段需要掌握的是：**每轮请求使用历史副本，成功后保存完整问答，失败时保留原历史。** 

## S02、调用工具

对话没问题了，然后发现文件中的信息要手动复制粘贴到对话中才行，那么能否让大模型直接读取文件呢？ 能，就是工具调用。

核心流程如下：

![](assets/agent-tool1.png)

细节说明：

1、调用工具是在调用大模型接口后，因为是大模型来决定要不要调用工具。

2、一次会话可能进行多次工具调用，也可能进行多次API调用。

因为在工具调用结果中，可能需要再次调用API接口

然后又因为不可预测性太多了，可能在死循环调用，可能卡死，可能空循环等等，所以需要添加一个最大调用次数限制，超过限制就停止调用。

核心代码逻辑如下：

```java
public void run(BufferedReader input, PrintWriter output) throws IOException {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", "你是一个编程助手，请用中文回答。需要操作或查看环境时使用 shell 工具，"
                + "根据真实工具结果回答。工具错误可修正后再次调用，不要把失败描述为成功。"));
        List<ToolDefinition> definitions = List.of(tool.definition());
        output.println("对话已启动，输入 exit 或 quit 退出。每次输入可触发多次工具调用。");
        output.println("工具> " + tool.definition().description());
        while (true) {
            output.print("你> ");
            output.flush();
            String line = input.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.strip()) || "quit".equalsIgnoreCase(line.strip())) {
                output.println("对话已结束。");
                output.flush();
                return;
            }
            if (StrUtil.isBlank(line)) {
                continue;
            }

            // 首次请求失败仍丢弃本轮；工具执行后保留完整协议历史，操作不能靠丢弃消息撤销。
            List<ChatMessage> request = new ArrayList<>(history);
            request.add(new ChatMessage("user", line));
            boolean toolsRecorded = false;
            try {
                for (int step = 1; step <= maxModelCalls; step++) {
                    ChatMessage answer = client.complete(List.copyOf(request), definitions);
                    request.add(answer);
                    if (StrUtil.isNotBlank(answer.content())) {
                        output.println("助手> " + answer.content());
                    }
                    if (answer.toolCalls().isEmpty()) {
                        history = request;
                        break;
                    }
                    boolean limitReached = step == maxModelCalls;
                    for (ToolCall call : answer.toolCalls()) {
                        output.println("工具请求> " + call.name() + " [" + call.id() + "] " + call.arguments());
                        output.flush();
                        String result = limitReached ? "工具未执行：已达到本轮模型调用上限。"
                                : executeTool(call);
                        request.add(ChatMessage.tool(call.id(), result));
                        output.println("工具结果> [" + call.id() + "] " + result);
                        output.flush();
                    }
                    history = request;
                    toolsRecorded = true;
                    if (limitReached) {
                        String reason = "已达到本轮模型调用上限（" + maxModelCalls + " 次），停止本轮；最后一批工具未执行。";
                        request.add(new ChatMessage("assistant", reason));
                        output.println("助手> " + reason);
                    }
                }
            } catch (HttpTimeoutException e) {
                output.println("请求超时；" + failureHint(toolsRecorded));
            } catch (IOException e) {
                output.println("请求失败：" + e.getMessage() + "；" + failureHint(toolsRecorded));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.println("请求已中断，对话结束。");
                output.flush();
                return;
            }
            output.flush();
        }
    }      
```

到这里，只是定义了一个工具，接下来定义多个工具。

工具接口：

```java
public interface AgentTool {
    ToolDefinition definition();

    String execute(String arguments) throws IOException, InterruptedException;
}

```

工具实现：（读取文件工具）

```java
public final class ReadFileTool implements AgentTool {
    private final WorkspaceFiles files;

    public ReadFileTool(WorkspaceFiles files) {
        this.files = files;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("read_file",
                "读取工作目录内的 UTF-8 文本文件（最多 1 MiB）。可用 limit 限制前几行；最多返回 12000 个内容字符。",
                JSON.parseObject("""
                        {"type":"object","properties":{"path":{"type":"string"},
                        "limit":{"type":"integer","minimum":1}},"required":["path"],"additionalProperties":false}
                        """));
    }

    @Override
    public String execute(String arguments) throws IOException {
        JSONObject args = ToolArguments.parse(arguments, Set.of("path", "limit"));
        String path = ToolArguments.string(args, "path", false);
        int limit = ToolArguments.positiveInt(args, "limit", Integer.MAX_VALUE);
        String content = files.read(files.resolve(path));
        if (content.isEmpty()) {
            return "[空文件]";
        }
        List<String> lines = content.lines().toList();
        String suffix = "";
        if (lines.size() > limit) {
            content = String.join("\n", lines.subList(0, limit));
            suffix = "\n[还有 " + (lines.size() - limit) + " 行未显示]";
        }
        if (content.length() > WorkspaceFiles.MAX_READ_CHARS) {
            content = content.substring(0, WorkspaceFiles.MAX_READ_CHARS);
            suffix += "\n[输出已截断，上限 12000 字符]";
        }
        return content + suffix;
    }
}
```

工具注册的原理就是将工具的名称与作用作为提示词来调用大模型接口，让大模型决策是否使用工具，使用哪个工具

# S03、权限规则

前面第二节的工具调用其实是比较危险的操作，例如修改文件，删除文件等，所以需要有权限来限制是否可以调用。

权限的原理也很简单，就是在调用工具之前进行检查与校验。

![](assets/agent-perm1.png)

权限检查会把调用分成三种：

- **默认运行**：读文件这类安全操作，直接执行。
- **需要确认**：删文件、跑命令这类危险操作，先问用户同不同意。
- **禁止**：不在白名单里，或者明显越界的调用，直接拦下。



代码实现如下，

1、权限规则说明

```java
 public static final String SYSTEM_PROMPT = ToolUseMain.SYSTEM_PROMPT
            + "写入、编辑和 Shell 调用需要程序向用户确认。权限拒绝表示工具没有执行。"
            + "尊重拒绝结果，不要换工具、改写命令或重复请求来绕过拒绝；说明结果并等待用户的新指示。";

// 教学用文本规则，不解析 Shell；可能误报，也不能覆盖变形、别名或编码后的命令。
    private static final List<DenyRule> DENY_RULES = List.of(
            new DenyRule(Pattern.compile(
                    "(?i)(?<![\\w-])(?:sudo|shutdown|reboot|mkfs|stop-computer|restart-computer|"
                            + "format-volume|clear-disk|remove-partition)(?:\\.exe)?(?![\\w-])"),
                    "命中提权、关机或磁盘操作的硬拒绝规则。"),
            new DenyRule(Pattern.compile("(?i)\\brm\\s+-(?:rf|fr)\\s+/\\s*(?:$|[;&|])"),
                    "命中删除根目录的硬拒绝规则。"),
            new DenyRule(Pattern.compile("(?i)(?:\\bdd\\s+if\\s*=|>\\s*/dev/sda)"),
                    "命中直接读写磁盘的硬拒绝规则。"));
```

权限注册到loop中

```

new ChatLoop(client, ToolUseMain.createTools(config, gate), config.maxModelCalls(), SYSTEM_PROMPT).run(input, output);
```

在工具调用前进行校验

```
public String execute(ToolCall call) throws InterruptedException {
        AgentTool tool = tools.get(call.name());
        if (tool == null) {
            return "工具错误：未知工具 " + call.name();
        }
        // S03 在已有分派点增加检查；失败时不调用 execute，也不影响调用 ID 的回传。
        try {
            String reason = permission.denialReason(call);
            if (reason != null) {
                return "权限拒绝：" + reason + " 工具未执行。";
            }
        } catch (IOException | RuntimeException e) {
            return "权限拒绝：无法完成权限检查（" + e.getClass().getSimpleName() + "），工具未执行。";
        }
        try {
            return tool.execute(call.arguments());
        } catch (IllegalArgumentException e) {
            return "工具错误：" + e.getMessage();
        } catch (IOException e) {
            return "工具错误：文件或命令操作失败（" + e.getClass().getSimpleName() + "）。";
        }
    }
```



# S04、HOOKS

在整个循环过程中，用户是不能修改任何循环流程与信息的，但是有些时候用户需要做自定义的修改，例如记录所有用户的输入。所以就有了Hooks，可以在循环的关键节点进行自定义扩展。

我们这里实现四种hook：

1、用户提示词记录前

2、工具调用前

3、工具调用后

4、任务完成后

![](assets/agent-hoots.png)

hook分两种，一种是内置的hook，即agent自己定义与实现的，一种是用户配置的hook，用户配置到json配置文件中，agent在执行时调用。

核心实现如下：

hook接口类

```java
/**
 * S04 的四个扩展点。按注册顺序同步执行，只需覆盖关心的事件。
 * 参数和消息快照不可修改；前置事件返回 null 放行，非 null（包括空串）拦截。
 */
public interface AgentHook {
    /** 用户提示词加入历史、请求模型之前；history 不包含本次 prompt。 */
    default String userPromptSubmit(String prompt, List<ChatMessage> history)
            throws IOException, InterruptedException {
        return null;
    }

    /** 已知工具执行和 S03 权限检查之前。 */
    default String preToolUse(ToolCall call) throws IOException, InterruptedException {
        return null;
    }

    /** 工具产生结果后，包括错误结果；耗时只计算工具执行，不能改写结果。 */
    default void postToolUse(ToolCall call, String result, long elapsedNanos) throws IOException {
    }

    /**
     * 本轮最终回复已保存和输出后；turn 包含本轮用户消息、工具往返和最终回复。
     * 不包含此前历史；超限、失败、中断或退出不触发，不能要求模型续跑。
     */
    default void stop(List<ChatMessage> turn) throws IOException {
    }
}
```

用户hook配置文件定义：

```json
{
  "hooks": {
    "UserPromptSubmit": [
      {
        "hooks": [
          { "type": "command", "command": "python examples/hooks/example_hook.py", "timeout": 10 }
        ]
      }
    ],
    "PreToolUse": [
      {
       ....
      }
    ]
  }
}

```

在启动时就加载hook配置，默认读取.agents/hook.json配置文件。

```
callbacks.addAll(HookTrust.load(projectDirectory, input, output, trustStore));
```

具体hook执行位置，用户输入后。

```
 // S04：过滤退出/空输入后，先触发钩子，再记录用户消息。
 String blocked = hooks.userPromptSubmit(line, history);
```

其他3个就不说明了，可以去对应的位置找。

hook实现后还不行，因为hook是执行的脚本命令，而脚本命令是很危险的操作，所以需要对脚本命令进行信任处理，信任方案如下：

首次hook执行时需要用户确认，用户确认后记录下来，下次就直接放行。

如果hook的命令与内容发生变化，需要用户重新确认。

运行中如果检测到hook命令发生了变化，也停止执行hook。
