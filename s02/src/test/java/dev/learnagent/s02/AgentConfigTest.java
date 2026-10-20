package dev.learnagent.s02;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {
    @TempDir
    Path directory;

    @Test
    void readsUtf8ConfigWithBomAndTrimsValues() throws IOException {
        Path file = write("\uFEFFopenai.base-url=https://example.com/v1\n"
                + "openai.model= 中文模型 \nopenai.api-key= test-secret \n");
        AgentConfig config = AgentConfig.load(file);
        assertEquals("https://example.com/v1", config.baseUrl());
        assertEquals("中文模型", config.model());
        assertEquals("test-secret", config.apiKey());
        assertFalse(config.toString().contains("test-secret"));
    }

    @Test
    void usesDefaultBaseUrlWhenOmitted() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=test-model\nopenai.api-key=test-secret\n"));
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertEquals(Path.of("playground"), config.workDirectory());
        assertEquals(10, config.maxModelCalls());
        assertEquals(30, config.shellTimeout().toSeconds());
        assertEquals(12000, config.maxOutputChars());
    }

    @Test
    void readsToolLimits() throws IOException {
        AgentConfig config = AgentConfig.load(write("openai.model=test\nopenai.api-key=test\n"
                + "agent.work-dir=练习\nagent.max-model-calls=3\nshell.timeout-seconds=2\nshell.max-output-chars=100\n"));
        assertEquals(Path.of("练习"), config.workDirectory());
        assertEquals(3, config.maxModelCalls());
        assertEquals(2, config.shellTimeout().toSeconds());
        assertEquals(100, config.maxOutputChars());
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent.max-model-calls=0", "agent.max-model-calls=-1",
            "shell.timeout-seconds=abc", "shell.max-output-chars=2147483648", "shell.max-output-chars="})
    void rejectsInvalidLimits(String setting) throws IOException {
        Path file = write("openai.model=test\nopenai.api-key=test\n" + setting + "\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file))
                .getMessage().contains(setting.split("=")[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai.model", "openai.api-key", "openai.base-url"})
    void rejectsBlankValuesAndNamesTheKey(String key) throws IOException {
        Path file = write("openai.model=test-model\nopenai.api-key=test-secret\n" + key + "=   \n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file));
        assertTrue(error.getMessage().contains(key));
        assertFalse(error.getMessage().contains("test-secret"));
    }

    @Test
    void missingRequiredKeyNamesTheKey() throws IOException {
        Path file = write("openai.model=test-model\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file))
                .getMessage().contains("openai.api-key"));
    }

    @Test
    void missingFileExplainsHowToCreateIt() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AgentConfig.load(directory.resolve("config.properties")));
        assertTrue(error.getMessage().contains("config.example.properties"));
    }

    @Test
    void malformedPropertiesDoNotLeakContents() throws IOException {
        Path file = write("openai.api-key=test-secret\\uZZZZ\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> AgentConfig.load(file));
        assertTrue(error.getMessage().contains("格式"));
        assertFalse(error.getMessage().contains("test-secret"));
    }

    private Path write(String contents) throws IOException {
        return Files.writeString(directory.resolve("config.properties"), contents, StandardCharsets.UTF_8);
    }
}
