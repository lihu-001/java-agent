package dev.learnagent.s08.permission;

import dev.learnagent.s08.tool.ToolCall;
import dev.learnagent.s08.tool.file.WorkspaceFiles;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

public final class PermissionPolicy {
    public enum Action {
        ALLOW, DENY, ASK
    }

    public record Decision(Action action, String reason) {
    }

    private record DenyRule(Pattern pattern, String reason) {
    }

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

    private final WorkspaceFiles files;

    public PermissionPolicy(WorkspaceFiles files) {
        this.files = files;
    }

    public Decision evaluate(ToolCall call) {
        JSONObject args;
        try {
            args = JSON.parseObject(call.arguments());
        } catch (RuntimeException e) {
            return new Decision(Action.DENY, "无法检查无效的 JSON 参数。");
        }
        if (args == null) {
            return new Decision(Action.DENY, "参数必须是 JSON 对象。");
        }
        try {
            return switch (call.name()) {
                case "shell" -> shellDecision(requiredString(args, "command"));
                case "read_file", "write_file", "edit_file" -> fileDecision(call.name(), args);
                case "glob" -> new Decision(Action.ALLOW, "使用 S02 的工作目录内查找。");
                // S05：只替换会话内存中的任务清单，不接触文件或进程。
                case "todo_write" -> new Decision(Action.ALLOW, "只更新会话内存中的任务清单。");
                // S06：派发本身只发起模型请求；子代理的每次工具调用仍各自经过权限检查。
                case "task" -> new Decision(Action.ALLOW, "派发子代理；其工具调用另行检查权限。");
                // S07：只读取技能目录中的说明文本，不接触工作目录或进程。
                case "load_skill" -> new Decision(Action.ALLOW, "只读取技能目录中的说明文本。");
                // S08：只登记压缩请求；完整记录写入工作目录的 .transcripts，不执行命令。
                case "compact" -> new Decision(Action.ALLOW, "只压缩会话上下文，完整记录保存到工作目录。");
                default -> new Decision(Action.DENY, "没有为该工具定义权限规则。");
            };
        } catch (IllegalArgumentException e) {
            return new Decision(Action.DENY, e.getMessage());
        } catch (IOException e) {
            return new Decision(Action.DENY, "无法验证文件路径（" + e.getClass().getSimpleName() + "）。");
        }
    }

    private Decision shellDecision(String command) {
        for (DenyRule rule : DENY_RULES) {
            if (rule.pattern().matcher(command).find()) {
                return new Decision(Action.DENY, rule.reason());
            }
        }
        return new Decision(Action.ASK, "Shell 以当前用户权限执行，可能修改文件或访问工作目录之外。");
    }

    private Decision fileDecision(String tool, JSONObject args) throws IOException {
        files.resolve(requiredString(args, "path"));
        return "read_file".equals(tool)
                ? new Decision(Action.ALLOW, "读取工作目录内的文件。")
                : new Decision(Action.ASK, "将写入或修改文件，请核对本次路径和内容。");
    }

    private String requiredString(JSONObject args, String key) {
        if (!(args.get(key) instanceof String value) || StrUtil.isBlank(value)) {
            throw new IllegalArgumentException(key + " 必须是非空字符串。");
        }
        return value;
    }
}
