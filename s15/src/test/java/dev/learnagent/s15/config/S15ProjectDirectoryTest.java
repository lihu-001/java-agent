package dev.learnagent.s15.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class S15ProjectDirectoryTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"s15/target/classes", "s15/target/java-agent-s15.jar",
            "out/production/java-agent-s15", "s15/out/production/classes"})
    void resolvesModuleFromMavenOrIdeaOutputWithoutConsultingParentHooks(String output) throws Exception {
        Path repository = root.resolve("中文 project");
        Path module = repository.resolve("s15");
        Files.createDirectories(module.resolve("src/main/java/dev/learnagent/s15"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repository.resolve(".agents"));
        Files.writeString(repository.resolve(".agents/hook.json"), "parent config must not be used");
        assertEquals(module, S15ProjectDirectory.fromCodeLocation(repository.resolve(output)));
        // 即使模块内没有 hook.json，也必须固定到 S05，不回退到父工程配置。
        assertFalse(Files.exists(module.resolve(".agents/hook.json")));
    }

    @Test
    void failsClearlyWhenModuleCannotBeLocatedInsteadOfUsingWorkingDirectory() throws IOException {
        Files.createDirectories(root.resolve(".agents"));
        Files.writeString(root.resolve(".agents/hook.json"), "{}");
        assertThrows(IllegalArgumentException.class,
                () -> S15ProjectDirectory.fromCodeLocation(root.resolve("target/classes")));
    }

    @Test
    void resolvesActualModuleFromItsLoadedClass() {
        Path module = S15ProjectDirectory.resolve();
        assertEquals("s15", module.getFileName().toString());
        assertTrue(Files.isRegularFile(module.resolve("pom.xml")));
        assertTrue(Files.isDirectory(module.resolve("src/main/java/dev/learnagent/s15")));
    }
}
