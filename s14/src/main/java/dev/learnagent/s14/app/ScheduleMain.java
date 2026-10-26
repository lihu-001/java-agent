package dev.learnagent.s14.app;

import dev.learnagent.s14.background.BackgroundManager;
import dev.learnagent.s14.background.BackgroundTools;
import dev.learnagent.s14.compact.CompactTool;
import dev.learnagent.s14.compact.ContextCompactor;
import dev.learnagent.s14.compact.TranscriptStore;
import dev.learnagent.s14.config.AgentConfig;
import dev.learnagent.s14.config.S14ProjectDirectory;
import dev.learnagent.s14.hook.AgentHook;
import dev.learnagent.s14.hook.AgentHooks;
import dev.learnagent.s14.hook.HookConfig;
import dev.learnagent.s14.hook.HookTrustStore;
import dev.learnagent.s14.loop.ChatLoop;
import dev.learnagent.s14.mcp.McpManager;
import dev.learnagent.s14.memory.MemoryDeleteTool;
import dev.learnagent.s14.memory.MemoryEntry;
import dev.learnagent.s14.memory.MemoryReadTool;
import dev.learnagent.s14.memory.MemoryStore;
import dev.learnagent.s14.memory.MemoryWriteTool;
import dev.learnagent.s14.model.ModelClient;
import dev.learnagent.s14.model.OpenAiClient;
import dev.learnagent.s14.permission.PermissionGate;
import dev.learnagent.s14.permission.PermissionPolicy;
import dev.learnagent.s14.schedule.ScheduleManager;
import dev.learnagent.s14.schedule.ScheduleStore;
import dev.learnagent.s14.schedule.ScheduleTools;
import dev.learnagent.s14.skill.LoadSkillTool;
import dev.learnagent.s14.skill.SkillCatalog;
import dev.learnagent.s14.subagent.SubagentRunner;
import dev.learnagent.s14.subagent.TaskTool;
import dev.learnagent.s14.task.TaskRecord;
import dev.learnagent.s14.task.TaskStatus;
import dev.learnagent.s14.task.TaskStore;
import dev.learnagent.s14.task.TaskTools;
import dev.learnagent.s14.todo.TodoList;
import dev.learnagent.s14.todo.TodoReminder;
import dev.learnagent.s14.todo.TodoWriteTool;
import dev.learnagent.s14.tool.AgentTool;
import dev.learnagent.s14.tool.ShellTool;
import dev.learnagent.s14.tool.ToolRegistry;
import dev.learnagent.s14.tool.file.WorkspaceFiles;

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
import java.util.ArrayList;
import java.util.List;

/**
 * S12 入口（上一课）：在 S11 全部能力之上增加持久化定时计划。
 * 计划只在本进程运行期间触发；到点命令交给后台执行，结果写回 .schedules，不进入对话通知。
 */
public final class ScheduleMain {
    public static final String SCHEDULE_RULE = "需要按固定时间重复执行的任务（定时构建、定期检查等）用 schedule_create 创建五段 cron 计划，"
            + "用 schedule_list / schedule_get 查看状态和上次输出。计划保存在工作目录 .schedules，跨会话保留，"
            + "但只有本程序运行期间才会触发，不是操作系统定时任务；进程没运行时错过的时点不补跑。"
            + "到点执行的是 Shell 命令，创建时确认一次，之后每次触发都无人值守执行，因此只调度你愿意让它自行运行的命令；"
            + "同一计划上一次还没结束时会跳过本次时点。结果不会以 <background-results> 送达，需要时用 schedule_get 读取。";

    /** 供嵌入与测试拿到调度器；生产入口只用 loop。S14：mcp 为本会话使用的 MCP 工具来源。 */
    public record Session(ChatLoop loop, BackgroundManager background, ScheduleManager schedules, McpManager mcp) {
    }

    private ScheduleMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createSession(config, client, input, output, S14ProjectDirectory.resolve(), List.of(),
                HookTrustStore.userStore(), new TodoList()).loop();
    }

    public static Session createSession(AgentConfig config, ModelClient client, BufferedReader input,
                                        PrintWriter output, Path projectDirectory,
                                        List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                        TodoList todos) throws IOException {
        return createSession(config, client, input, output, projectDirectory, agentCallbacks, trustStore, todos,
                ZoneId.systemDefault(), Clock.systemDefaultZone(), true);
    }

    /** 显式时区、时钟和是否立即启动调度线程，用于可重复的测试；调度器仍使用同一套文件与后台执行器。 */
    public static Session createSession(AgentConfig config, ModelClient client, BufferedReader input,
                                        PrintWriter output, Path projectDirectory,
                                        List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                        TodoList todos, ZoneId zone, Clock clock, boolean startScheduler)
            throws IOException {
        return createSession(config, client, input, output, projectDirectory, agentCallbacks, trustStore, todos,
                zone, clock, startScheduler, McpManager.Starter.none());
    }

    /**
     * S14：Hook 信任确认之后用同一输入启动 MCP server；工具追加在最后、只给主 Agent，系统提示词附 MCP 规则。
     * Starter.none() 时与 S12 完全相同。组装失败时关闭已启动的 server，成功后由调用方在会话结束时关闭 Session.mcp。
     */
    public static Session createSession(AgentConfig config, ModelClient client, BufferedReader input,
                                        PrintWriter output, Path projectDirectory,
                                        List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                        TodoList todos, ZoneId zone, Clock clock, boolean startScheduler,
                                        McpManager.Starter mcpStarter) throws IOException {
        AgentHooks hooks = HooksMain.createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        McpManager mcp = mcpStarter.start(input, output);
        try {
            return assemble(config, client, input, output, projectDirectory, hooks, todos, zone, clock,
                    startScheduler, mcp);
        } catch (IOException | RuntimeException e) {
            mcp.close();
            throw e;
        }
    }

    private static Session assemble(AgentConfig config, ModelClient client, BufferedReader input,
                                    PrintWriter output, Path projectDirectory, AgentHooks hooks, TodoList todos,
                                    ZoneId zone, Clock clock, boolean startScheduler, McpManager mcp)
            throws IOException {
        SkillCatalog catalog = SkillCatalog.scan(projectDirectory, warning -> print(output, warning));
        print(output, "技能> 已加载目录 " + catalog.skills().size() + " 个：" + (catalog.skills().isEmpty() ? "无"
                : String.join("、", catalog.skills().stream().map(skill -> skill.name()).toList())));
        WorkspaceFiles files = new WorkspaceFiles(config.workDirectory());
        MemoryStore memories = new MemoryStore(files.root().resolve(MemoryStore.DIRECTORY_NAME));
        List<MemoryEntry> entries = memories.list(warning -> print(output, warning));
        print(output, "记忆> 已加载索引 " + entries.size() + " 条：" + (entries.isEmpty() ? "无"
                : String.join("、", entries.stream().map(MemoryEntry::name).toList())));
        TaskStore tasks = new TaskStore(files.root().resolve(TaskStore.DIRECTORY_NAME));
        List<TaskRecord> saved = tasks.list(warning -> print(output, warning));
        long completed = saved.stream().filter(task -> task.status() == TaskStatus.COMPLETED).count();
        print(output, "任务> " + TaskStore.DIRECTORY_NAME + " 中有 " + saved.size() + " 个任务，已完成 " + completed + " 个");
        PermissionGate gate = new PermissionGate(new PermissionPolicy(files, mcp.allowed()), input, output);
        // 子代理与 S11 相同：没有后台工具，也没有定时计划，不会在无人接收的情况下产生输出。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                SkillMain.systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        ContextCompactor compactor = new ContextCompactor(client,
                new TranscriptStore(files.root().resolve(TranscriptStore.DIRECTORY_NAME)), files.root(),
                config.compactThresholdChars(), config.compactKeepToolResults(),
                () -> todos.items().isEmpty() ? null : todos.render());
        ShellTool backgroundShell = new ShellTool(files.root(), config.backgroundTimeout(), config.maxOutputChars());
        BackgroundManager background = new BackgroundManager(backgroundShell, line -> print(output, line));
        ScheduleStore schedules = new ScheduleStore(files.root().resolve(ScheduleStore.DIRECTORY_NAME));
        ScheduleManager scheduleManager = new ScheduleManager(schedules, background,
                Duration.ofSeconds(config.schedulerPollSeconds()), zone, clock, line -> print(output, line));
        List<AgentTool> extra = new ArrayList<>(List.of(new TodoWriteTool(todos),
                new TaskTool(runner), new LoadSkillTool(catalog), new CompactTool(compactor),
                new MemoryReadTool(memories), new MemoryWriteTool(memories), new MemoryDeleteTool(memories)));
        extra.addAll(TaskTools.create(tasks, TaskTools.DEFAULT_OWNER));
        extra.addAll(BackgroundTools.create(background, "工作目录 " + files.root() + "，命令超时 "
                + config.backgroundTimeout().toSeconds() + " 秒，最多保留 " + config.maxOutputChars() + " 个输出字符。"));
        extra.addAll(ScheduleTools.create(scheduleManager, "工作目录 " + files.root() + "，命令超时 "
                + config.backgroundTimeout().toSeconds() + " 秒。"));
        extra.addAll(mcp.tools());
        ToolRegistry registry = ToolUseMain.createTools(config, gate, hooks, extra);
        ChatLoop loop = new ChatLoop(client, registry, config.maxModelCalls(),
                systemPrompt(catalog, entries) + mcp.promptSuffix(), hooks, new TodoReminder(todos), compactor, background);
        if (startScheduler) {
            scheduleManager.start();
        }
        return new Session(loop, background, scheduleManager, mcp);
    }

    /** S11 系统提示词之后附加定时计划规则。 */
    public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries) {
        return BackgroundMain.systemPrompt(catalog, entries) + "\n\n" + SCHEDULE_RULE;
    }

    private static void print(PrintWriter output, String line) {
        synchronized (output) {
            output.println(line);
            output.flush();
        }
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
            Path projectDirectory = S14ProjectDirectory.resolve();
            ChatLoop loop = createLoop(config, client, input, output);
            output.println("S12 Cron Scheduler（上一课入口）：用 schedule_create 创建五段 cron 计划（本机时区，最短一分钟），"
                    + "每 " + config.schedulerPollSeconds() + " 秒检查一次；计划只在本次进程运行期间触发，错过时点不补跑。");
            output.println("保留 S03 权限、S04 Hook、S05 任务清单、S06 子代理、S07 技能、S08 压缩、S09 记忆、"
                    + "S10 任务图与 S11 后台任务。Hook 配置> " + projectDirectory.resolve(HookConfig.DEFAULT_PATH));
            loop.run(input, output);
        } catch (IllegalArgumentException e) {
            output.println("配置错误：" + e.getMessage());
        } catch (IOException e) {
            output.println("Hook 配置读取、技能、记忆、任务或计划目录扫描、工作目录初始化或终端读取失败（"
                    + e.getClass().getSimpleName() + "），对话结束。");
        } finally {
            output.flush();
        }
    }
}
