package dev.learnagent.s04;

import dev.learnagent.s01.AgentConfig;
import dev.learnagent.s01.ChatLoop;
import dev.learnagent.s01.ModelClient;
import dev.learnagent.s01.OpenAiClient;
import dev.learnagent.s01.ToolRegistry;
import dev.learnagent.s02.ToolUseMain;
import dev.learnagent.s02.WorkspaceFiles;
import dev.learnagent.s03.PermissionGate;
import dev.learnagent.s03.PermissionMain;
import dev.learnagent.s03.PermissionPolicy;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class HooksMain {
    public static final String SYSTEM_PROMPT = PermissionMain.SYSTEM_PROMPT
            + "工具执行前后有程序钩子；钩子拒绝也表示工具未执行，应尊重拒绝并等待用户的新指示。";

    private HooksMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createLoop(config, client, input, output, List.of(new LoggingHook(output)));
    }

    /** 自定义 Hook 只注册一次，四类事件共享同一条链。 */
    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input,
                                     PrintWriter output, List<? extends AgentHook> callbacks) throws IOException {
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files), input, output);
        AgentHooks hooks = new AgentHooks(callbacks, message -> {
            output.println(message);
            output.flush();
        });
        ToolRegistry tools = ToolUseMain.createTools(config, gate, hooks);
        return new ChatLoop(client, tools, config.maxModelCalls(), SYSTEM_PROMPT, hooks);
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
            ChatLoop loop = createLoop(config, client, input, output);
            output.println("S04 Hooks：UserPromptSubmit / PreToolUse / PostToolUse / Stop。");
            output.println("用户提示词记录前、工具调用前后、本轮任务完成后触发；保留 S03 权限检查。");
            loop.run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("工作目录初始化或终端读取失败（" + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
