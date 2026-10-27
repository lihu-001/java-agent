package dev.learnagent.s16.app;

import dev.learnagent.s16.background.BackgroundManager;
import dev.learnagent.s16.background.BackgroundStatus;
import dev.learnagent.s16.background.BackgroundTools;
import dev.learnagent.s16.compact.CompactTool;
import dev.learnagent.s16.compact.ContextCompactor;
import dev.learnagent.s16.compact.TranscriptStore;
import dev.learnagent.s16.config.AgentConfig;
import dev.learnagent.s16.config.S16ProjectDirectory;
import dev.learnagent.s16.hook.AgentHook;
import dev.learnagent.s16.hook.AgentHooks;
import dev.learnagent.s16.hook.HookConfig;
import dev.learnagent.s16.hook.HookTrustStore;
import dev.learnagent.s16.harness.HarnessOptions;
import dev.learnagent.s16.loop.ChatLoop;
import dev.learnagent.s16.mcp.McpManager;
import dev.learnagent.s16.memory.MemoryDeleteTool;
import dev.learnagent.s16.memory.MemoryEntry;
import dev.learnagent.s16.memory.MemoryReadTool;
import dev.learnagent.s16.memory.MemoryStore;
import dev.learnagent.s16.memory.MemoryWriteTool;
import dev.learnagent.s16.model.ModelClient;
import dev.learnagent.s16.model.OpenAiClient;
import dev.learnagent.s16.permission.PermissionGate;
import dev.learnagent.s16.permission.PermissionPolicy;
import dev.learnagent.s16.schedule.ScheduleManager;
import dev.learnagent.s16.schedule.ScheduleStore;
import dev.learnagent.s16.schedule.ScheduleTools;
import dev.learnagent.s16.skill.LoadSkillTool;
import dev.learnagent.s16.skill.SkillCatalog;
import dev.learnagent.s16.subagent.SubagentRunner;
import dev.learnagent.s16.subagent.TaskTool;
import dev.learnagent.s16.task.TaskRecord;
import dev.learnagent.s16.task.TaskStatus;
import dev.learnagent.s16.task.TaskStore;
import dev.learnagent.s16.task.TaskTools;
import dev.learnagent.s16.todo.TodoList;
import dev.learnagent.s16.todo.TodoReminder;
import dev.learnagent.s16.todo.TodoWriteTool;
import dev.learnagent.s16.tool.AgentTool;
import dev.learnagent.s16.tool.ShellTool;
import dev.learnagent.s16.tool.ToolRegistry;
import dev.learnagent.s16.tool.file.WorkspaceFiles;
import dev.learnagent.s16.workflow.WorkflowBinding;
import dev.learnagent.s16.workflow.WorkflowStarter;

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

    /**
     * S15：HarnessOptions 启用时附加在定时规则之后；上一课入口不带这条，提示词与 S14 完全相同。
     */
    public static final String SCHEDULE_EVENT_RULE = "定时计划的每次运行结束（除会话结束导致的取消）会以 <system-events>"
            + " 自动送达并唤醒你，不需要轮询；进程退出期间的终态看不到，需要历史输出时用 schedule_get 读取。";

    /** 供嵌入与测试拿到调度器；生产入口只用 loop。S14：mcp 为本会话使用的 MCP 工具来源。 */
    public record Session(ChatLoop loop, BackgroundManager background, ScheduleManager schedules, McpManager mcp) {
    }

    private ScheduleMain() {
    }

    public static ChatLoop createLoop(AgentConfig config, ModelClient client, BufferedReader input, PrintWriter output)
            throws IOException {
        return createSession(config, client, input, output, S16ProjectDirectory.resolve(), List.of(),
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
                    startScheduler, mcp, null, null);
        } catch (IOException | RuntimeException e) {
            mcp.close();
            throw e;
        }
    }

    /**
     * S15：options 非 null 时启用事件唤醒、限流退避、响应式压缩与哨兵轮的免确认；
     * null 时与 S12/S14 完全相同。循环输入由调用方用 options.loopInput 运行。
     */
    public static Session createSession(AgentConfig config, ModelClient client, BufferedReader input,
                                        PrintWriter output, Path projectDirectory,
                                        List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                        TodoList todos, ZoneId zone, Clock clock, boolean startScheduler,
                                        McpManager.Starter mcpStarter, HarnessOptions options) throws IOException {
        return createSession(config, client, input, output, projectDirectory, agentCallbacks, trustStore, todos,
                zone, clock, startScheduler, mcpStarter, options, null);
    }

    /**
     * S16：workflows 非 null 时主 Agent 获得五个工作流工具并在启动时修复遗留 running 状态；
     * null 时与 S15 完全相同（上一课入口）。
     */
    public static Session createSession(AgentConfig config, ModelClient client, BufferedReader input,
                                        PrintWriter output, Path projectDirectory,
                                        List<? extends AgentHook> agentCallbacks, HookTrustStore trustStore,
                                        TodoList todos, ZoneId zone, Clock clock, boolean startScheduler,
                                        McpManager.Starter mcpStarter, HarnessOptions options,
                                        WorkflowStarter workflows) throws IOException {
        AgentHooks hooks = HooksMain.createHooks(projectDirectory, agentCallbacks, trustStore, input, output);
        McpManager mcp = mcpStarter.start(input, output);
        try {
            return assemble(config, client, input, output, projectDirectory, hooks, todos, zone, clock,
                    startScheduler, mcp, options, workflows);
        } catch (IOException | RuntimeException e) {
            mcp.close();
            throw e;
        }
    }

    private static Session assemble(AgentConfig config, ModelClient client, BufferedReader input,
                                    PrintWriter output, Path projectDirectory, AgentHooks hooks, TodoList todos,
                                    ZoneId zone, Clock clock, boolean startScheduler, McpManager mcp,
                                    HarnessOptions options, WorkflowStarter workflows)
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
        // S15：哨兵轮中 ASK 自动拒绝；事件中心接收定时计划的非取消终态，后台完成只负责唤醒。
        PermissionGate gate = options == null
                ? new PermissionGate(new PermissionPolicy(files, mcp.allowed()), input, output)
                : new PermissionGate(new PermissionPolicy(files, mcp.allowed()), input, output,
                        options.events()::inSyntheticTurn);
        // 子代理与 S11 相同：没有后台工具，也没有定时计划，不会在无人接收的情况下产生输出。
        ToolRegistry childTools = ToolUseMain.createTools(config, gate, hooks, List.of(new LoadSkillTool(catalog)));
        SubagentRunner runner = new SubagentRunner(client, childTools, config.maxModelCalls(),
                SkillMain.systemPrompt(SubagentMain.SUBAGENT_PROMPT, catalog), output);
        // S16：工作流步骤复用同一个子代理运行器；null 时与 S15 完全相同。
        WorkflowBinding workflow = workflows == null ? null : workflows.bind(runner, output);
        ContextCompactor compactor = new ContextCompactor(client,
                new TranscriptStore(files.root().resolve(TranscriptStore.DIRECTORY_NAME)), files.root(),
                config.compactThresholdChars(), config.compactKeepToolResults(),
                () -> todos.items().isEmpty() ? null : todos.render());
        ShellTool backgroundShell = new ShellTool(files.root(), config.backgroundTimeout(), config.maxOutputChars());
        BackgroundManager background = new BackgroundManager(backgroundShell, line -> print(output, line));
        ScheduleStore schedules = new ScheduleStore(files.root().resolve(ScheduleStore.DIRECTORY_NAME));
        ScheduleManager scheduleManager = options == null
                ? new ScheduleManager(schedules, background,
                        Duration.ofSeconds(config.schedulerPollSeconds()), zone, clock, line -> print(output, line))
                : new ScheduleManager(schedules, background,
                        Duration.ofSeconds(config.schedulerPollSeconds()), zone, clock,
                        line -> print(output, line), options.events()::publish);
        if (options != null) {
            background.addCompletionListener(completion -> {
                if (completion.source() == null && completion.status() != BackgroundStatus.CANCELLED) {
                    options.events().wakeup();
                }
            });
        }
        List<AgentTool> extra = new ArrayList<>(List.of(new TodoWriteTool(todos),
                new TaskTool(runner), new LoadSkillTool(catalog), new CompactTool(compactor),
                new MemoryReadTool(memories), new MemoryWriteTool(memories), new MemoryDeleteTool(memories)));
        extra.addAll(TaskTools.create(tasks, TaskTools.DEFAULT_OWNER));
        extra.addAll(BackgroundTools.create(background, "工作目录 " + files.root() + "，命令超时 "
                + config.backgroundTimeout().toSeconds() + " 秒，最多保留 " + config.maxOutputChars() + " 个输出字符。"));
        extra.addAll(ScheduleTools.create(scheduleManager, "工作目录 " + files.root() + "，命令超时 "
                + config.backgroundTimeout().toSeconds() + " 秒。"));
        if (workflow != null) {
            extra.addAll(workflow.tools());
        }
        extra.addAll(mcp.tools());
        ToolRegistry registry = ToolUseMain.createTools(config, gate, hooks, extra);
        // S16：工作流规则附加在 MCP 规则之后；workflows 为 null 时提示词与 S15 逐字相同。
        String workflowSuffix = workflow == null ? "" : "\n\n" + workflow.rule();
        ChatLoop loop = options == null
                ? new ChatLoop(client, registry, config.maxModelCalls(),
                        systemPrompt(catalog, entries) + mcp.promptSuffix() + workflowSuffix, hooks,
                        new TodoReminder(todos), compactor, background)
                : new ChatLoop(client, registry, config.maxModelCalls(),
                        systemPrompt(catalog, entries, true) + mcp.promptSuffix() + workflowSuffix, hooks,
                        new TodoReminder(todos), compactor, background, options.events(), options.retry());
        if (startScheduler) {
            scheduleManager.start();
        }
        return new Session(loop, background, scheduleManager, mcp);
    }

    /** S11 系统提示词之后附加定时计划规则。 */
    public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries) {
        return BackgroundMain.systemPrompt(catalog, entries) + "\n\n" + SCHEDULE_RULE;
    }

    /** S15：事件唤醒可用时附加终态送达说明。 */
    public static String systemPrompt(SkillCatalog catalog, List<MemoryEntry> entries, boolean scheduledEvents) {
        return systemPrompt(catalog, entries) + (scheduledEvents ? "\n\n" + SCHEDULE_EVENT_RULE : "");
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
            Path projectDirectory = S16ProjectDirectory.resolve();
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
