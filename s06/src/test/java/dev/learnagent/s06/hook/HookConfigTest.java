package dev.learnagent.s06.hook;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HookConfigTest {
    @TempDir
    Path root;

    @Test
    void missingOrEmptyConfigurationLeavesDefaultBehaviorAvailable() throws Exception {
        assertTrue(HookConfig.load(root, message -> fail(message)).isEmpty());
        write("\uFEFF{\"hooks\":{}}");
        assertTrue(HookConfig.load(root, message -> fail(message)).isEmpty());
    }

    @Test
    void loadingValidConfigurationDoesNotExecuteCommands() throws Exception {
        write(JSON.toJSONString(Map.of("hooks", Map.of("PreToolUse", List.of(Map.of(
                "matcher", "shell|write_file", "hooks", List.of(Map.of(
                        "type", "command", "command", "this_command_does_not_exist", "timeout", 5))))))));
        assertEquals(1, HookConfig.load(root, message -> fail(message)).size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "not-json", "null", "[]", "{}", "{\"hooks\":null}",
            "{\"hooks\":{\"Typo\":[]}}",
            "{\"hooks\":{},\"extra\":true}",
            "{\"hooks\":{},\"trusted\":true}",
            "{\"hooks\":{\"Stop\":{}}}",
            "{\"hooks\":{\"Stop\":[null]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[] ,\"matcher\":\"*\"}]}}",
            "{\"hooks\":{\"PreToolUse\":[{\"matcher\":\"[\",\"hooks\":[]}]}}",
            "{\"hooks\":{\"PreToolUse\":[{\"matcher\":42,\"hooks\":[]}]}}",
            "{\"hooks\":{\"PreToolUse\":[{\"hooks\":[],\"typo\":true}]}}",
            "{\"hooks\":{\"Stop\":[{}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"prompt\",\"command\":\"echo ok\"}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\" \"}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":3}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo ok\",\"extra\":1}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo ok\",\"trust_files\":null}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo ok\",\"trust_files\":\"a.py\"}]}]}}",
            "{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\",\"command\":\"echo ok\",\"trust_files\":[\"\"]}]}]}}"
    })
    void rejectsInvalidConfigurationInsteadOfSilentlyDisablingHooks(String text) throws Exception {
        write(text);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HookConfig.load(root, message -> { }));
        assertTrue(error.getMessage().contains("hook.json"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "301", "1.5", "\"5\"", "null", "true", "999999999999"})
    void requiresBoundedIntegerTimeout(String timeout) throws Exception {
        write("{\"hooks\":{\"Stop\":[{\"hooks\":[{\"type\":\"command\","
                + "\"command\":\"echo ok\",\"timeout\":" + timeout + "}]}]}}");
        assertThrows(IllegalArgumentException.class, () -> HookConfig.load(root, message -> { }));
    }

    @Test
    void unreadableConfigurationIsNotTreatedAsMissing() throws Exception {
        Files.createDirectories(root.resolve(HookConfig.DEFAULT_PATH));
        assertThrows(IOException.class, () -> HookConfig.load(root, message -> { }));
    }

    private void write(String text) throws IOException {
        Path config = root.resolve(HookConfig.DEFAULT_PATH);
        Files.createDirectories(config.getParent());
        Files.writeString(config, text);
    }
}
