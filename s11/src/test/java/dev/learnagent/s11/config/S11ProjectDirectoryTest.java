package dev.learnagent.s11.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class S11ProjectDirectoryTest {
    @TempDir
    Path root;

    @ParameterizedTest
    @ValueSource(strings = {"s11/target/classes", "s11/target/java-agent-s11.jar",
            "out/production/java-agent-s11", "s11/out/production/classes"})
    void resolvesModuleFromMavenOrIdeaOutputWithoutConsultingParentHooks(String output) throws Exception {
        Path repository = root.resolve("中文 project");
        Path module = repository.resolve("s11");
        Files.createDirectories(module.resolve("src/main/java/dev/learnagent/s11"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repository.resolve(".agents"));
        Files.writeString(repository.resolve(".agents/hook.json"), "parent config must not be used");
        assertEquals(module, S11ProjectDirectory.fromCodeLocation(repository.resolve(output)));
        // 即使模块内没有 hook.json，也必须固定到 S05，不回退到父工程配置。
        assertFalse(Files.exists(module.resolve(".agents/hook.json")));
    }

    @Test
    void failsClearlyWhenModuleCannotBeLocatedInsteadOfUsingWorkingDirectory() throws IOException {
        Files.createDirectories(root.resolve(".agents"));
        Files.writeString(root.resolve(".agents/hook.json"), "{}");
        assertThrows(IllegalArgumentException.class,
                () -> S11ProjectDirectory.fromCodeLocation(root.resolve("target/classes")));
    }

    @Test
    void resolvesActualModuleFromItsLoadedClass() {
        Path module = S11ProjectDirectory.resolve();
        assertEquals("s11", module.getFileName().toString());
        assertTrue(Files.isRegularFile(module.resolve("pom.xml")));
        assertTrue(Files.isDirectory(module.resolve("src/main/java/dev/learnagent/s11")));
    }
}
