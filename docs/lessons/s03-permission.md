# S03：Permission

## 1. 原理：在工具真正执行前做决策

S02 已经能够把模型返回的工具名称和参数交给 `ToolRegistry`。例如模型提出 `write_file`，注册表就找到对应工具并写文件。

S03 在这个调用点增加权限检查。模型仍然负责提出操作，程序规则和用户负责决定是否执行。仅在系统提示词里告诉模型“先询问用户”不够：真正的检查必须发生在 Java 调用 `tool.execute()` 之前。

```text
模型返回 ToolCall
    ↓
ToolRegistry 找到工具
    ↓
PermissionPolicy 检查参数、路径和硬拒绝规则
    ├─ ALLOW → 执行原有工具
    ├─ DENY  → 不执行，返回权限拒绝
    └─ ASK   → 显示本次调用 → 用户输入 y/yes？
                                  ├─ 是 → 执行原有工具
                                  └─ 否或 EOF → 返回权限拒绝
    ↓
ChatLoop 把执行结果或拒绝原因绑定原 call_id 回传模型
```

这里有三个要点：

1. **权限判断与工具实现分开。** 文件工具负责读写，权限层负责是否允许；工具仍执行自己的参数和路径校验。
2. **拒绝也是一次调用的结果。** 不能只跳过工具而省略 `tool` 消息，否则后续请求会携带缺少结果的工具调用。
3. **用户只批准眼前这一次调用。** 同一个文件下一次写入、下一条 Shell 命令，都要重新检查和确认；答案不进入聊天历史。

## 2. 本课规则

按以下顺序作出决定：

| 情况 | 决策 | 原因 |
| --- | --- | --- |
| 未知工具 | 返回未知工具错误，不询问 | 注册表没有对应执行器 |
| 参数 JSON 无效，或权限所需的 `path` / `command` 无效 | DENY | 无法判断要执行什么 |
| 文件路径规范化后越界，或已有目录链接指向工作区外 | DENY | 继续使用 S02 的路径限制 |
| Shell 命中硬拒绝规则 | DENY | 硬拒绝不能由一次 `yes` 覆盖 |
| 工作区内 `read_file`、`glob` | ALLOW | 仍由 S02 工具检查完整参数和路径模式 |
| 工作区内 `write_file`、`edit_file` | ASK | 包括新建文件和覆盖已有文件 |
| 其他 Shell 命令 | ASK | Shell 能以当前用户权限操作文件、进程和网络 |
| 新注册但未定义权限规则的工具 | DENY | 不自动放行未知能力 |

示例硬拒绝规则包括提权、关机、磁盘格式化、直接写磁盘及 `rm -rf /` 形式的根目录删除；补充了 Windows PowerShell 的 `Stop-Computer`、`Restart-Computer`、`Format-Volume`、`Clear-Disk` 和 `Remove-Partition` 等关键词。

这是用于演示规则优先级的文本匹配，不是 Shell 解释器。例如 `echo sudo` 也会命中关键词；变形、别名、脚本或编码后的指令可能不命中，因此其余 Shell 调用也都需要确认。批准 Shell 不会把它限制在工作目录内。文件路径检查也不防御硬链接或并发替换路径。不要把这些机制称为系统沙箱。

确认只接受忽略大小写及首尾空白的 `y`、`yes`。空行、`n`、其他文字和 EOF 均拒绝。确认处输入 `exit` 或 `quit` 也表示拒绝当前调用；要退出对话，在下一次 `你>` 提示处输入退出命令。

## 3. 在 S02 上增量实现

本课没有新增 `ChatLoop`，也没有复制任何文件工具。

| 文件 | 改动 |
| --- | --- |
| `s01/ToolPermission.java` | 执行前检查接口：返回 `null` 表示允许，返回文本表示拒绝原因 |
| `s01/ToolRegistry.java` | 增加带权限检查器的构造方式，在 `tool.execute()` 前检查 |
| `s02/ToolUseMain.java` | 原工具组装方法增加权限参数，S03 直接复用相同的五个工具 |
| `s02/WorkspaceFiles.java` | 将已有 `resolve` 开放给权限策略，复用同一套路径检查 |
| `s03/PermissionPolicy.java` | 纯决策逻辑，输出 `ALLOW`、`DENY` 或 `ASK` 及原因，不执行工具 |
| `s03/PermissionGate.java` | 处理询问，使用与对话循环相同的输入输出流 |
| `s03/PermissionMain.java` | 组装策略、询问器和 S02 工具，运行已有 `s01.ChatLoop` |

注册表保留原构造方式：

```java
new ToolRegistry(tools);             // S01 / S02 的原行为
new ToolRegistry(tools, permission); // S03 增加执行前检查
```

核心新增逻辑是：

```java
String reason = permission.denialReason(call);
if (reason != null) {
    return "权限拒绝：" + reason + " 工具未执行。";
}
return tool.execute(call.arguments());
```

权限输入读取失败或检查器抛出运行时异常时，注册表返回权限拒绝，工具不会执行；中断继续交给原循环结束对话。达到模型调用上限时，原循环直接补齐“工具未执行”的结果，连权限询问也不会触发。

S01 与 S02 的入口仍使用不带权限检查器的构造方式。本课不增加可配置规则文件、记住授权、自动审批模式或执行前后扩展接口；后者属于 S04。

## 4. 启动和观察

沿用项目根目录的 `config.properties`，没有新增配置项。使用 JDK 17：

```powershell
.\mvnw.cmd package
.\mvnw.cmd -q compile exec:java "-Dexec.mainClass=dev.learnagent.s03.PermissionMain"
```

输入：

```text
只用 write_file 在 s03-demo/note.txt 写入“第一版”，如果被拒绝就停止，不要换工具重试。
```

模型提出调用后，终端显示本次工具名、调用 ID、完整参数及询问原因：

```text
权限确认> 将写入或修改文件，请核对本次路径和内容。
工具> write_file [模型生成的调用ID]
参数> {"path":"s03-demo/note.txt","content":"第一版"}
仅允许本次调用？[y/N]
```

先输入 `n`，确认工具结果为权限拒绝，文件未创建。下一轮明确发出新的写入请求，再输入 `y`，确认文件写入。然后要求用 `read_file` 读取，观察读取不询问。

使用重定向输入做测试时，确认答案也从标准输入按顺序读取。不要把普通聊天内容排在需要确认的位置；当前实现遇到无法识别的确认内容会消费这一行并拒绝。

## 5. 动手练习

1. 批准新建文件，再拒绝编辑同一个文件；读回验证授权没有延续到第二次调用。
2. 发起一个无副作用的 Shell 命令，例如 `echo hello`；先拒绝，再在新一轮请求中批准。
3. 请求执行 `echo sudo`，观察示例硬拒绝关键词直接生效；不要使用真正危险的命令练习。
4. 要求文件工具写入 `../outside.txt`，观察程序直接拒绝越界请求。
5. 将模型调用上限临时设为 `1`，观察最后一批调用既不询问也不执行，然后恢复配置。

## 6. 与上游的差异和验证

对应固定版本的 [s03_permission/code.py](https://github.com/shareAI-lab/learn-claude-code/blob/ce8f9f186058939da54c9d6fead78dfb5d0fd6c3/s03_permission/code.py)。沿用“硬拒绝 → 操作规则 → 用户确认”的机制，以及拒绝结果回传。

Java 版保留 S02 的工作目录限制，越界文件操作直接拒绝；对所有写入、编辑及未命中硬拒绝的 Shell 调用逐次询问。上游主要在命中风险规则时询问。这些差异使本课默认行为更容易观察，也适配当前 Java 项目使用的 PowerShell。

自动测试使用模拟模型、临时文件及无副作用的真实 Shell 命令，验证规则优先级、确认输入、拒绝无副作用、逐次授权、EOF、输入失败、混合调用 ID、历史和调用上限。硬拒绝中的危险命令只作为策略测试字符串，不实际执行。构建和真实模型验收见[实战方案](../JAVA_PRACTICE_SPEC.md#s03-验证记录)。
