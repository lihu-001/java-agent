# S03：工具权限处理

复制 S02 的完整能力，加入执行前权限。读取和查找默认允许；写入、编辑与 Shell 逐次确认，硬拒绝规则不可覆盖。没有 Hook 类。

## 本课代码与增量

| 文件/类 | 本课职责或相较上一课的变化 |
| --- | --- |
| `ToolPermission` | 新增权限接口 |
| `PermissionPolicy` | 新增 ALLOW / DENY / ASK 规则 |
| `PermissionGate` | 新增仅允许本次的 y/yes 确认 |
| `ToolRegistry / ToolUseMain.createTools` | 新增权限检查器注入 |
| `PermissionMain` | 默认入口，组装权限和工具 |
| `ChatLoop / 工具 / HTTP 客户端` | 沿用本模块内的 S02 阶段代码 |

生产源码位于 [src/main/java/dev/learnagent/s03](src/main/java/dev/learnagent/s03)，测试位于 [src/test/java/dev/learnagent/s03](src/test/java/dev/learnagent/s03)。没有其他课的 Java 依赖；下一课复制本课源码后修改，不影响本课。

`SingleToolLoopTest` 验证单工具，`ChatLoopTest` 验证多工具。后续模块保留前一课入口/组装类用于对照，Maven 默认启动本课入口。

## 单独运行

在本目录内：

```powershell
..\mvnw.cmd test
..\mvnw.cmd package
..\mvnw.cmd -q compile exec:java
```

默认入口 `PermissionMain`，不用指定 exec.mainClass。也可用本机 Maven：`mvn test` / `mvn -q compile exec:java`。父 POM 通过相对路径读取，不需要安装其他课程。

将本目录 `config.example.properties` 复制为 `config.properties` 并填写。或在仓库根运行 `.\mvnw.cmd -pl s03 -q compile exec:java`，使用根目录已有配置。配置与相对工作目录均以进程启动目录为准。

机制与练习见 [课程讲解](../docs/lessons/s03-permission.md)。
