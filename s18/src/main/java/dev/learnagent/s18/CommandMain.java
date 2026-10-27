package dev.learnagent.s18;

import dev.learnagent.s18.app.TeamMain;
import dev.learnagent.s18.command.CommandStarter;
import dev.learnagent.s18.config.AgentConfig;
import dev.learnagent.s18.config.S18ProjectDirectory;
import dev.learnagent.s18.goal.GoalStarter;
import dev.learnagent.s18.harness.AgentConsole;
import dev.learnagent.s18.harness.EventHub;
import dev.learnagent.s18.harness.HarnessOptions;
import dev.learnagent.s18.harness.ModelRetryPolicy;
import dev.learnagent.s18.hook.HookConfig;
import dev.learnagent.s18.hook.HookTrustStore;
import dev.learnagent.s18.mcp.McpManager;
import dev.learnagent.s18.mcp.McpTrustStore;
import dev.learnagent.s18.model.ModelClient;
import dev.learnagent.s18.model.OpenAiClient;
import dev.learnagent.s18.team.TeamManager;
import dev.learnagent.s18.todo.TodoList;
import dev.learnagent.s18.workflow.WorkflowStarter;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

/**
 * S18 入口：在 S17 全部能力之上增加斜杠命令。
 * 终端里以 / 开头的输入先交给命令注册表：/clear、/compact 操作对话历史，/skills、/mcp、/status 等查看会话状态，
 * 这些本地命令不请求模型、不触发 Hook、不进入历史；/goal 与 /<技能名> 是提示词命令，展开后作为本轮任务交给模型。
 * S17 的 GoalMain 保留在 app 包作为上一课入口，不接入斜杠命令。
 */
public final class CommandMain {
    private CommandMain() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        BufferedReader input = new BufferedReader(console == null
                ? new InputStreamReader(System.in, StandardCharsets.UTF_8) : console.reader());
        PrintWriter output = console == null
                ? new PrintWriter(System.out, true, StandardCharsets.UTF_8) : console.writer();
        try {
            AgentConfig config = AgentConfig.load(Path.of("config.properties"));
            ModelClient client = new OpenAiClient(config.baseUrl(), config.apiKey(),
                    config.model(), Duration.ofSeconds(60));
            // 判断器可以单独配置一个更便宜的模型；未配置时与主 Agent 共用客户端。
            ModelClient evaluator = config.goalEvaluatorModel() == null ? client
                    : new OpenAiClient(config.baseUrl(), config.apiKey(), config.goalEvaluatorModel(),
                    Duration.ofSeconds(60));
            Path projectDirectory = S18ProjectDirectory.resolve();
            AgentConsole agentConsole = new AgentConsole(input, output);
            EventHub events = new EventHub();
            // 事件到达（定时入队、后台完成唤醒）时叫醒空闲的循环。
            events.addListener(agentConsole::notifyEvents);
            HarnessOptions options = new HarnessOptions(events,
                    new ModelRetryPolicy(ModelRetryPolicy.DEFAULT_MAX_RETRIES), agentConsole.prompts());
            WorkflowStarter workflows = WorkflowStarter.of(config);
            GoalStarter goals = GoalStarter.of(evaluator, config.goalBlockCap());
            TeamMain.Session session = TeamMain.createSession(config, client, agentConsole.answers(), output,
                    projectDirectory, List.of(), HookTrustStore.userStore(), new TodoList(),
                    ZoneId.systemDefault(), Clock.systemDefaultZone(), true, TeamManager.DEFAULT_IDLE_SCAN,
                    McpManager.Starter.of(projectDirectory, McpTrustStore.userStore(), System.getenv()),
                    options, workflows, goals, CommandStarter.builtin());
            List<String> servers = session.mcp().servers();
            output.println("S18 Slash Commands：S01—S17 全部机制加斜杠命令（/help 查看全部；/clear 清空对话，"
                    + "/compact 立即压缩，/skills、/mcp、/status 等查看状态，/<技能名> 直接调用技能），"
                    + "本地命令不请求模型；MCP 已连接 " + servers.size() + " 个 server"
                    + (servers.isEmpty() ? "" : "：" + String.join("、", servers)) + "。");
            output.println("团队模式" + (session.teamEnabled() ? "已开启" : "未开启（team.enabled=false）")
                    + "；Hook 配置> " + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            session.run();
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 或 MCP 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
