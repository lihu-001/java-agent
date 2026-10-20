# S03：工具权限处理

源码位于 `s03/src/main/java/dev/learnagent/s03/`。本课复制 S02 完整源码后增加权限处理，无跨模块 Java 依赖，也没有 Hook 类。

## 相较 S02 的增量

新增 ToolPermission、PermissionPolicy、PermissionGate、PermissionMain。ToolRegistry 接收权限检查器，ToolUseMain.createTools 增加权限参数；循环和五个工具沿用本模块内的 S02 阶段实现。

```text
工具调用 → 找到工具 → 权限策略
                       ├─ ALLOW：执行
                       ├─ DENY：返回拒绝
                       └─ ASK：显示参数、终端确认
                                  ├─ y/yes：仅允许本次
                                  └─ 其他输入/EOF：拒绝
         → 按原调用 ID 回传结果
```

## 默认规则

| 调用 | 行为 |
| --- | --- |
| 工作目录内 read_file、glob | 直接允许 |
| write_file、edit_file | 逐次询问 |
| Shell 命中硬拒绝 | 直接拒绝 |
| 其余 Shell | 逐次询问 |
| 越界文件路径、无效参数或检查失败 | 拒绝 |

硬拒绝不能通过确认覆盖。只接受 y/yes，忽略大小写和首尾空白；其他输入包括确认处的 exit/quit 均拒绝。确认只适用于当前调用，不累积授权。

确认与 ChatLoop 共用输入流，y/n 不进入聊天历史。拒绝也产生正确 ID 的 tool 结果。提示词要求模型尊重拒绝，不换工具绕过。权限检查异常则不执行，中断传播给原循环。

Shell 规则是教学用关键词/正则，可能误报且不能识别所有变形。工作区检查和许可均不是系统沙箱，获批 Shell 仍以当前用户权限运行。

## 运行和练习

```powershell
.\mvnw.cmd -pl s03 -q compile exec:java
```

要求仅用 write_file 在 s03-demo/note.txt 写入第一版，被拒绝就停止；输入 n，确认文件未创建。下一轮明确重新请求，输入 y；再 read_file 读回，无需确认。请求 edit_file 修改时仍会询问，上次批准不会沿用。

## 阅读和测试

阅读 PermissionMain 组装、PermissionPolicy 规则、PermissionGate 交互，再对照 S02 与本模块的 ToolRegistry。ToolUseMain 保留作前一课的组装基础；Maven 默认入口是 PermissionMain。

```powershell
.\mvnw.cmd -pl s03 test
```

保留工具回归，新增 PermissionPolicyTest、PermissionGateTest、PermissionLoopTest，覆盖三种决策、越界、确认输入、EOF、异常、中断和结果 ID。危险命令仅作为分类字符串，不真实执行；执行测试只使用临时目录和无副作用命令。
