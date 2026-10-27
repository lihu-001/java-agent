package dev.learnagent.s18.command;

import dev.learnagent.s18.background.BackgroundManager;
import dev.learnagent.s18.compact.ContextCompactor;
import dev.learnagent.s18.compact.TranscriptStore;
import dev.learnagent.s18.config.AgentConfig;
import dev.learnagent.s18.goal.GoalController;
import dev.learnagent.s18.hook.AgentHooks;
import dev.learnagent.s18.mcp.McpManager;
import dev.learnagent.s18.memory.MemoryStore;
import dev.learnagent.s18.schedule.ScheduleStore;
import dev.learnagent.s18.skill.SkillCatalog;
import dev.learnagent.s18.task.TaskStore;
import dev.learnagent.s18.team.TeamManager;
import dev.learnagent.s18.todo.TodoList;
import dev.learnagent.s18.workflow.WorkflowStore;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 组装点交给命令的会话部件，全部属于主 Agent（团队模式为 Lead）：查看命令读取的存储与管理器，
 * 以及 /clear、/compact、/export 用到的清单、目标、压缩器与完整记录。
 * workflows、team、goal 为 null 表示本会话没有该机制，对应命令不注册。
 */
public record CommandSources(AgentConfig config, Path projectDirectory, SkillCatalog catalog, McpManager mcp,
                             AgentHooks hooks, MemoryStore memories, TodoList todos, TaskStore tasks,
                             BackgroundManager background, ScheduleStore schedules, ZoneId zone,
                             WorkflowStore workflows, TeamManager team, GoalController goal,
                             ContextCompactor compactor, TranscriptStore transcripts) {
    public CommandSources {
        Objects.requireNonNull(config);
        Objects.requireNonNull(projectDirectory);
        Objects.requireNonNull(catalog);
        Objects.requireNonNull(mcp);
        Objects.requireNonNull(hooks);
        Objects.requireNonNull(memories);
        Objects.requireNonNull(todos);
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(background);
        Objects.requireNonNull(schedules);
        Objects.requireNonNull(zone);
        Objects.requireNonNull(compactor);
        Objects.requireNonNull(transcripts);
    }
}
