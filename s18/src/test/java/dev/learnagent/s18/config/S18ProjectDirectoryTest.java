package dev.learnagent.s18.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class S18ProjectDirectoryTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"s18/target/classes", "s18/target/java-agent-s18.jar",
            "out/production/java-agent-s18", "s18/out/production/classes"})
    void resolvesModuleFromMavenOrIdeaOutputWithoutConsultingParentHooks(String output) throws Exception {
        Path repository = root.resolve("中文 project");
        Path module = repository.resolve("s18");
        Files.createDirectories(module.resolve("src/main/java/dev/learnagent/s18"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repository.resolve(".agents"));
        Files.writeString(repository.resolve(".agents/hook.json"), "parent config must not be used");
        assertEquals(module, S18ProjectDirectory.fromCodeLocation(repository.resolve(output)));
        // 即使模块内没有 hook.json，也必须固定到 S05，不回退到父工程配置。
        assertFalse(Files.exists(module.resolve(".agents/hook.json")));
    }

    @Test
    void failsClearlyWhenModuleCannotBeLocatedInsteadOfUsingWorkingDirectory() throws IOException {
        Files.createDirectories(root.resolve(".agents"));
        Files.writeString(root.resolve(".agents/hook.json"), "{}");
        assertThrows(IllegalArgumentException.class,
                () -> S18ProjectDirectory.fromCodeLocation(root.resolve("target/classes")));
    }

    @Test
    void resolvesActualModuleFromItsLoadedClass() {
        Path module = S18ProjectDirectory.resolve();
        assertEquals("s18", module.getFileName().toString());
        assertTrue(Files.isRegularFile(module.resolve("pom.xml")));
        assertTrue(Files.isDirectory(module.resolve("src/main/java/dev/learnagent/s18")));
    }
}
