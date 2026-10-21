package dev.learnagent.s07.config;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 从本模块的编译产物定位源码工程，不使用进程工作目录或父工程的 Hook 配置。 */
public final class S07ProjectDirectory {
    private S07ProjectDirectory() {
    }

    public static Path resolve() {
        try {
            return fromCodeLocation(Path.of(S07ProjectDirectory.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无法定位 S07 子项目目录", e);
        }
    }

    static Path fromCodeLocation(Path codeLocation) {
        for (Path candidate = codeLocation.toAbsolutePath().normalize();
             candidate != null; candidate = candidate.getParent()) {
            // Maven 的 s07/target/classes 或 s07/target/*.jar。
            if (isModule(candidate)) {
                return candidate;
            }
            // IDEA 也可能将产物放在仓库根 out/production/<module>。
            Path module = candidate.resolve("s07");
            if (isModule(module)) {
                return module;
            }
        }
        throw new IllegalArgumentException("无法定位 S07 子项目目录，请从包含 s07 源码的工程运行");
    }

    private static boolean isModule(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("src/main/java/dev/learnagent/s07"));
    }
}
