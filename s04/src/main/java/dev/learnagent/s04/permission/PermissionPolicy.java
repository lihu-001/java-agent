package dev.learnagent.s04.permission;

import dev.learnagent.s04.tool.ToolCall;
import dev.learnagent.s04.tool.file.WorkspaceFiles;

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
