# 用 Java 创建 Agent：先跑通对话循环

---

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



# S05、TodoWrite 任务清单

到这里，Agent 已经能连续调用多个工具了。但任务一复杂，例如“新建目录、写三个文件、再统计行数”，问题就来了：计划只存在于模型某一次回复的文字里，后面工具结果越堆越多，模型很容易漏掉步骤，或者做到一半就说“完成了”。

解决办法是让模型把计划写下来，并且随时更新进度，这就是 TodoWrite。

它本质上还是一个工具，叫 `todo_write`，和 S02 的读写文件工具没有区别：

1、**任务分解由大模型完成**。模型收到多步骤任务后，自己决定拆成哪几步，调用 `todo_write` 提交清单。

2、**程序只负责校验和保存**。程序不生成步骤，也不判断某一步是否真的完成，只检查格式。校验通过后，清单在两个地方各有一份：

- **上下文中**：渲染后的清单作为工具结果写入对话历史，大模型就是从这里看到计划和进度的。
- **程序内存中**：`TodoList` 对象保存当前清单和版本号，只供程序自己使用，比如校验状态、判断是否还有未完成项、触发提醒。大模型读不到这个对象。

3、**进度也由模型更新**。每开始或完成一步，模型就重新提交一份完整清单。

核心流程如下：

![](agent_todowrite.png)



每项任务有三种状态：

- **pending**：待处理
- **in_progress**：进行中，同一时间最多一项，让模型一次专注一步
- **completed**：已完成

工具返回给模型的结果是这样的：

```text
已更新。任务清单（1/3 完成）：
[x] 查找 txt 文件
[>] 读取并合并
[ ] 写入 all.txt 并统计行数
```

这段文本会进入对话历史，所以模型每次请求都能看到最新的计划和进度。

核心实现如下：

1、任务清单，每次整体替换，校验失败时保持原状

```java
public synchronized void replace(List<TodoItem> next) {
    if (next.size() > MAX_ITEMS) {
        throw new IllegalArgumentException("任务最多 " + MAX_ITEMS + " 项。");
    }
    List<TodoItem> normalized = new ArrayList<>();
    int inProgress = 0;
    for (TodoItem item : next) {
        String content = StrUtil.trim(item.content());
        if (StrUtil.isEmpty(content)) {
            throw new IllegalArgumentException("任务内容不能为空。");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("单项任务内容最多 " + MAX_CONTENT_CHARS + " 字符。");
        }
        if (item.status() == TodoStatus.IN_PROGRESS && ++inProgress > 1) {
            throw new IllegalArgumentException("同一时间只能有一项 in_progress。");
        }
        normalized.add(new TodoItem(content, item.status()));
    }
    items = List.copyOf(normalized);
    version++;
}
```

为什么是整体替换，而不是按编号增删某一项？因为实现简单，也避免模型和程序对编号的理解不一致。

2、工具定义，告诉模型怎么提交清单

```java
return new ToolDefinition("todo_write",
        "更新本次会话的任务清单。每次提交完整清单，整体替换旧清单；空数组表示清空。"
                + "status 为 pending、in_progress 或 completed，同一时间最多一项 in_progress。",
        JSON.parseObject("""
                {"type":"object","properties":{"todos":{"type":"array","items":{"type":"object",
                "properties":{"content":{"type":"string"},
                "status":{"type":"string","enum":["pending","in_progress","completed"]}},
                "required":["content","status"],"additionalProperties":false}}},
                "required":["todos"],"additionalProperties":false}
                """));
```

3、系统提示词，引导模型什么时候用清单

```java
public static final String SYSTEM_PROMPT = HooksMain.SYSTEM_PROMPT
        + "需要三步以上的任务，先用 todo_write 列出步骤；开始某步前将其标记为 in_progress，"
        + "完成后立即标记为 completed，同一时间只有一项 in_progress。每次提交完整清单。"
        + "简单问答不需要任务清单。";
```

4、注册为第六个工具，和前面的权限、Hook 共用

```java
ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks, List.of(new TodoWriteTool(todos)));
return new ChatLoop(client, tools, config.maxModelCalls(), SYSTEM_PROMPT, hooks, new TodoReminder(todos));
```

`todo_write` 只修改内存，所以权限直接放行，不需要用户确认：

```java
// S05：只替换会话内存中的任务清单，不接触文件或进程。
case "todo_write" -> new Decision(Action.ALLOW, "只更新会话内存中的任务清单。");
```

但它仍然会经过 S04 的 PreToolUse / PostToolUse Hook，用户可以通过 Hook 拦截它。

**提醒机制**

模型有可能列完清单就忘了更新。所以加了一个提醒：如果模型连续 3 批工具调用都没有更新清单，并且还有未完成的任务，程序就在这批最后一个工具结果后面附加一段提醒。

```java
// S05：提醒附加在本批最后一个工具结果后，不新增消息角色，协议顺序不变。
String nudge = reminder == null || limitReached ? null : reminder.afterToolBatch();
if (nudge != null) {
    int last = request.size() - 1;
    ChatMessage tool = request.get(last);
    request.set(last, ChatMessage.tool(tool.toolCallId(), tool.content() + "\n\n" + nudge));
}
```

为什么附加在工具结果上，而不是新加一条消息？因为 OpenAI 协议要求 assistant 发起的 tool_calls 后面必须紧跟对应的 tool 结果，中间插入其他消息可能导致请求报错。

提醒的规则：

- 每次用户输入时计数清零
- 本批调用过 todo_write，计数清零
- 全部完成后不再提醒
- 达到模型调用上限的那一批不提醒，因为不会再请求模型了

提醒只是提示，模型仍然可以忽略。

**清单存在哪里？**

对大模型来说，清单在上下文中：每次 `todo_write` 返回的渲染文本都留在对话历史里。程序另外在会话内存中保留一份 `TodoList`，用于校验和提醒。两份都只在当前会话有效，同一会话跨轮保留，退出就丢失，不写文件。对话历史和内存清单同时清空，状态是一致的。

TodoWrite 的定位是单次会话的“工作草稿”，帮模型在长任务中不跑偏。跨会话、可恢复的任务管理，后面的 Task System 再做。



# S06、Subagent 子代理

S05 解决了"计划会丢"的问题，但还有一个问题：上下文会被撑满。

比如让 Agent "找出 playground 里所有 txt 文件，看看各自是干什么的"。模型会先 glob，再一个个 read_file，十几个工具结果全都留在对话历史里。任务做完了，这些文件内容还在，后面每次请求都要重新发给模型。既浪费 token，也会干扰模型回答后面的问题。

解决办法是把这类子任务交给一个"子代理"去做：它在一份独立的历史里干活，干完只交回一段总结，中间过程不进入主对话。这就是 Subagent。

它本质上还是一个工具，叫 `task`：

1、**派发由大模型决定**。主代理觉得某个子任务需要大量查找、阅读，就调用 `task`，把任务说明写进 `prompt`。

2、**子代理从零开始**。子代理看不到主对话，只能看到两条消息：自己的系统提示词，和主代理写的 `prompt`。所以 `prompt` 必须写清目标、路径和期望返回什么。

3、**只交回结果**。子代理反复请求模型、调用工具，直到给出最终回复。这段回复作为 `task` 的工具结果写入主历史，子代理的历史直接丢弃。

核心流程如下：

```text
主代理历史：system → user → assistant(task) → tool(子代理总结) → assistant(最终回复)
                                   │                 ▲
                                   ▼                 │
子代理历史（临时）：system → user(prompt) → assistant → tool → … → assistant(总结)
```

主代理调用时是这样的：

```json
{"description":"查看文件","prompt":"在工作目录中找出所有 .txt 文件，读取后总结各自的用途，返回文件路径列表和一句话说明。"}
```

`description` 是简称，只用来在终端打印日志；`prompt` 才是交给子代理的任务。主代理只看到子代理的总结，看不到子代理读到的文件全文。

核心实现如下：

1、子代理循环，关键是**新建历史，而不是复制主历史**

```java
public Result run(String description, String prompt) throws IOException, InterruptedException {
    // 关键点：不复制父代理历史，子代理只看到系统提示词和本次任务。
    List<ChatMessage> messages = new ArrayList<>();
    messages.add(new ChatMessage("system", systemPrompt));
    messages.add(new ChatMessage("user", prompt));
    for (int step = 1; step <= maxModelCalls; step++) {
        ChatMessage answer = client.complete(List.copyOf(messages), tools.definitions());
        messages.add(answer);
        if (answer.toolCalls().isEmpty()) {
            return new Result(answer.content().strip(), step, toolCalls, true);   // 只返回最终文字
        }
        if (step == maxModelCalls) {
            break;   // 最后一批工具不执行
        }
        for (ToolCall call : answer.toolCalls()) {
            messages.add(ChatMessage.tool(call.id(), tools.execute(call)));
        }
    }
    return new Result("子代理未完成：已达到模型调用上限……", maxModelCalls, toolCalls, false);
}
```

`messages` 是方法里的局部变量，方法返回，子代理的整段历史就没了。这就是"任务过程不交给主代理"的全部实现，没有任何额外的清理代码。

它和 S01 的对话循环几乎一样，只是输入从终端换成了 `prompt` 参数，结束条件从用户输入 exit 换成了模型不再调用工具。

2、工具定义，告诉主代理怎么写 `prompt`

```java
return new ToolDefinition("task",
        "派发一个子代理独立完成子任务。子代理看不到当前对话，prompt 必须写清目标、相关路径和期望的返回内容。"
                + "子代理可用工具：shell、read_file、write_file、edit_file、glob；它不能再派发子代理，也不共享任务清单。"
                + "适合需要大量查找、读取的探索任务，只返回最终总结，中间过程不进入当前对话。",
        JSON.parseObject("""
                {"type":"object","properties":{
                "description":{"type":"string","description":"3-10 字的任务简称，用于日志"},
                "prompt":{"type":"string","description":"交给子代理的完整任务说明"}},
                "required":["description","prompt"],"additionalProperties":false}
                """));
```

3、两套工具，共用同一套权限和 Hook

```java
AgentHooks hooks = HooksMain.createHooks(...);
PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
// 子代理：五个基础工具
ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks);
SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(), SUBAGENT_PROMPT, output);
// 主代理：五个基础工具 + todo_write + task
ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks,
        List.of(new TodoWriteTool(todos), new TaskTool(runner)));
```

子代理的工具里**没有 `task`**，所以它不能再派生子代理，不会无限套娃；**也没有 `todo_write`**，所以它改不了主代理的任务清单。子代理真去调用这两个工具，只会拿到"未知工具"错误。

**权限和 Hook 怎么复用？**

S03 的权限检查和 S04 的工具 Hook，都放在 `ToolRegistry.execute` 这个分派方法里，不在循环里。主、子代理的循环不同，但调用工具都经过同一个分派方法，用的也是同一个 `gate` 和 `hooks` 对象，所以子代理不需要另写一套。

子代理是在 `task` 工具内部运行的，所以检查是嵌套的：

```text
主 registry.execute(task)
 ├─ PreToolUse(task)          可拦截，拦截后子代理不启动
 ├─ 权限(task) → 直接允许
 ├─ 子代理运行
 │    └─ 子 registry.execute(write_file)
 │         ├─ PreToolUse(write_file)
 │         ├─ 权限 → 终端确认 [y/N]
 │         ├─ 执行
 │         └─ PostToolUse(write_file)
 └─ PostToolUse(task)         结果是子代理总结
```

`task` 本身直接放行，因为它只是发起模型请求。真正有副作用的写文件、Shell 都在子代理里，那一层照常要用户确认，所以子代理绕不过权限。

```java
// S06：派发本身只发起模型请求；子代理的每次工具调用仍各自经过权限检查。
case "task" -> new Decision(Action.ALLOW, "派发子代理；其工具调用另行检查权限。");
```

UserPromptSubmit 和 Stop 只属于主循环，子代理不触发：子代理的输入不是用户输入，它的回复也不是本轮的最终回复。

**每个 task 是一个线程吗？**

不是。子代理就在主代理的同一个线程里同步运行，本质上就是一次方法调用。`task` 返回之前，主代理一直等着。模型一次返回两个 `task`，也是一个跑完再跑下一个。

这样最简单：终端确认只有一个人在读，日志顺序也是确定的。所谓"独立的子代理"，靠的是数据隔离（每次新建一份历史），和线程无关。多个子代理并行执行，要处理确认排队、输出交错、线程安全和结果顺序，后面的 Background Tasks、Agent Teams 再做。

**子代理的边界**

- 子代理每次 `task` 最多请求模型 `agent.max-model-calls` 次，和主代理分开计数。到上限就停，返回"子代理未完成"和最后的说明。
- 子代理的模型请求失败或超时，变成 `task` 的工具错误交给主代理，主代理可以重试或者自己动手。已经执行的操作不会回滚。
- 结果超过 12000 字符会截断，避免一个子代理的总结又把主上下文撑满。

Subagent 的定位是"用一次性的上下文换取主对话的干净"。代价是子代理不知道主对话的背景，主代理必须把任务交代清楚，`prompt` 写得好不好，直接决定子代理干得好不好。
