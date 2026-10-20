# S04：四类 Hook

复制 S03 完整能力，加入 UserPromptSubmit、PreToolUse、PostToolUse、Stop。Java Hook 是 Agent 的内置行为；用户通过 S04 子项目下的 `.agents/hook.json` 配置命令 Hook。用户 Hook 经独立信任确认后与 Java Hook 同时生效，用户无需修改 Java。

## 包结构

根包只保留默认入口 `HooksMain`，其余按职责分包：

```text
dev.learnagent.s04
├── HooksMain                 # 默认入口，组装完整 S04
├── app                       # 前两课保留的工具/权限组装入口
├── config                    # AgentConfig、S04ProjectDirectory
├── loop                      # ChatLoop：输入、历史与模型/工具循环
├── model                     # ChatMessage、ModelClient、OpenAiClient
├── tool                      # 工具接口、调用数据、注册表和 Shell
│   └── file                  # 四个文件工具、WorkspaceFiles、ToolArguments
├── permission                # 权限接口、策略与逐次确认
└── hook                      # 回调接口、分派、日志、JSON 配置和命令执行
```

从 [HooksMain](src/main/java/dev/learnagent/s04/HooksMain.java) 看组装；学习 Hook 时重点读 [hook](src/main/java/dev/learnagent/s04/hook)，再看 [loop/ChatLoop](src/main/java/dev/learnagent/s04/loop/ChatLoop.java) 和 [tool/ToolRegistry](src/main/java/dev/learnagent/s04/tool/ToolRegistry.java) 的触发点。对应测试按相同子包组织。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `hook.AgentHook` | 新增四事件接口 |
| `hook.AgentHooks` | 新增有序分派、短路、异常隔离和消息快照 |
| `hook.LoggingHook` | 四类事件日志示例 |
| `config.S04ProjectDirectory` | 从本模块编译产物定位 S04 子项目，不依赖进程工作目录 |
| `hook.HookConfig` | 加载 `.agents/hook.json`，校验事件、匹配器、命令与超时 |
| `hook.HookTrust / HookTrustStore / HookTrustSnapshot` | 用户 Hook 启用确认、用户目录授权记录、配置和脚本内容变更检测 |
| `hook.CommandHook / hook.HookCommandRunner` | 生成事件 JSON，通过 stdin 交给命令，处理退出码、超时和输出上限 |
| `loop.ChatLoop` | 新增提示词记录前和最终回复后事件 |
| `tool.ToolRegistry / app.ToolUseMain.createTools` | 新增工具前后事件和共享事件链注入 |
| `HooksMain` | 合并内置日志、追加的 Agent Java Hook、用户 JSON Hook，按此顺序注册 |

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

将本目录 `config.example.properties` 复制为 `config.properties` 并填写。或在仓库根运行 `.\mvnw.cmd -pl s04 -q compile exec:java`，使用根目录已有模型配置。`config.properties` 与工具工作目录仍以进程启动目录为准；Hook 配置和命令工作目录固定到 S04 子项目。

## JSON 配置 Hook

从仓库根、S04 目录或 IDEA 启动，都读取本模块 [`.agents/hook.json`](.agents/hook.json)，不读取或合并父工程的 Hook 配置。命令的工作目录固定为 S04，因此脚本写 `python examples/hooks/example_hook.py`。与 `agent.work-dir` 无关，修改配置后需重启。

配置缺失或内容为 `{"hooks": {}}` 时只执行内置 Java Hook。完整四事件示例见 [hook.example.json](.agents/hook.example.json)，将示例内容写入本模块的 `hook.json` 即可启用；示例脚本需要 Python 3，核心 Java 实现没有新增 Python 依赖。

两类 Hook 会共同执行：内置日志 → Agent 追加的 Java Hook → 用户 JSON Hook。`HooksMain.createLoop(..., List.of(new MyHook()))` 只追加 Java Hook，仍自动注册日志、加载用户 JSON，无需手动重复注册。前置检查中任一拒绝会短路后续检查，后置回调异常彼此隔离。

首次启用或配置/脚本内容变化时，会展示事件、命令、执行目录和跟踪文件，并询问：

```text
启用用户 Hook？[y=信任并记住 / once=仅本次 / N=不启用]
```

`y/yes` 将授权保存到用户目录 `~/.java-agent/hook-trust/`，下次相同项目及相同内容不再询问；`once` 仅当前会话有效；其他输入或 EOF 不启用用户 Hook，内置 Java Hook 继续执行。信任确认答案不进入模型历史，信任 Hook 也不会批准 S03 的工具调用。

自动跟踪命令中明确写出的 `.py`、`.ps1`、`.sh` 等字面量脚本路径；脚本间接读取的依赖可用命令配置的 `trust_files` 数组补充。运行中检测到配置或跟踪文件变化后，本会话停止执行用户 Hook，需要重启确认；不会在后置事件中突然消费聊天输入。删除启动时显示的授权记录文件，可以在下次启动撤销该项目的记忆授权。

不需要脚本也可直接定义命令，例如禁止 Shell：

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "shell",
        "hooks": [
          { "type": "command", "command": "echo shell-disabled; exit 2", "timeout": 10 }
        ]
      }
    ]
  }
}
```

命令通过 UTF-8 标准输入接收事件 JSON。前置退出码 `0` 放行，`2` 拦截，其他非零值、执行错误和超时也拦截；后置失败只告警。放行仍需经过 S03 权限检查。Windows 使用 PowerShell，Unix 使用 `/bin/sh`；Hook 命令以当前用户权限执行，启用信任与工具逐次确认是两套机制。内容摘要不分析脚本行为，也不覆盖解释器、动态依赖或文件校验与执行之间的并发替换，不能替代系统沙箱。

字段、输入协议及 Java 扩展方式见 [课程讲解](../docs/lessons/s04-hooks.md#3-json-配置命令-hook)。
