package dev.learnagent.s03;

import com.alibaba.fastjson2.JSON;
import dev.learnagent.s01.ToolCall;
import dev.learnagent.s02.WorkspaceFiles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static dev.learnagent.s03.PermissionPolicy.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class PermissionPolicyTest {
    @TempDir
    Path root;
    private PermissionPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        policy = new PermissionPolicy(new WorkspaceFiles(root));
    }

    @Test
    void readsAndGlobAreAllowedWhileWritesAndEditsAsk() {
        assertEquals(ALLOW, check("read_file", Map.of("path", "note.txt")).action());
        assertEquals(ALLOW, check("glob", Map.of("pattern", "**/*.txt")).action());
        assertEquals(ASK, check("write_file", Map.of("path", "new/note.txt", "content", "x")).action());
        assertEquals(ASK, check("edit_file", Map.of("path", "note.txt", "old_text", "x", "new_text", "y")).action());
        assertEquals(ASK, check("write_file", Map.of("path", root.resolve("inside.txt").toString())).action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Get-ChildItem -Name", "Get-Content example.txt", "echo hello",
            "git status", "echo first; echo second", "Remove-Item demo.txt", "rm demo.txt"})
    void everyShellCommandWithoutAHardDenyMatchAsks(String command) {
        assertEquals(ASK, check("shell", Map.of("command", command)).action());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sudo id", "shutdown /s", "SHUTDOWN.EXE /s", "reboot", "mkfs.ext4 /dev/test",
            "dd if=/dev/zero", "echo data > /dev/sda", "rm -rf /", "rm -fr / ; echo x",
            "Stop-Computer", "Restart-Computer", "Format-Volume", "Clear-Disk", "Remove-Partition"})
    void hardDenyCommandsAreClassifiedWithoutExecution(String command) {
        assertEquals(DENY, check("shell", Map.of("command", command)).action());
    }

    @Test
    void pathsOutsideWorkspaceAreDeniedForAllFileOperations() {
        for (String tool : new String[]{"read_file", "write_file", "edit_file"}) {
            for (String path : new String[]{"../outside.txt", root.resolveSibling("other/file.txt").toString()}) {
                PermissionPolicy.Decision decision = check(tool, Map.of("path", path));
                assertEquals(DENY, decision.action());
                assertTrue(decision.reason().contains("工作目录"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "bad json", "{}", "{\"path\":12}", "{\"path\":\" \"}"})
    void invalidPermissionArgumentsAreDenied(String arguments) {
        assertEquals(DENY, policy.evaluate(new ToolCall("id", "write_file", arguments)).action());
    }

    @Test
    void invalidCommandAndUnknownToolAreDenied() {
        assertEquals(DENY, check("shell", Map.of("command", 12)).action());
        assertEquals(DENY, check("shell", Map.of("command", " ")).action());
        assertEquals(DENY, check("unregistered", Map.of()).action());
    }

    private PermissionPolicy.Decision check(String tool, Map<String, Object> args) {
        return policy.evaluate(new ToolCall("id", tool, JSON.toJSONString(args)));
    }
}
