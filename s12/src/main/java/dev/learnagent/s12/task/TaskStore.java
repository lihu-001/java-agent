package dev.learnagent.s12.task;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 持久化任务图：工作目录 .tasks/ 下每个任务一个 task_xxxxxxxx.json 文件，文件是唯一的数据源。
 * 每次操作都重新读取文件，进程重启或手动编辑后状态立即可见；修改先写临时文件再替换。
 */
public final class TaskStore {
    public static final String DIRECTORY_NAME = ".tasks";
    public static final int MAX_TASKS = 200;
    public static final int MAX_SUBJECT_CHARS = 200;
    public static final int MAX_DESCRIPTION_CHARS = 4000;
    public static final int MAX_DEPENDENCIES = 20;
    public static final int MAX_OWNER_CHARS = 64;
    static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Pattern ID = Pattern.compile("task_[0-9a-f]{8}");
    private static final Set<String> FIELDS = Set.of("id", "subject", "description", "status", "owner", "blockedBy");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path directory;

    public TaskStore(Path directory) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    /** 新建任务：状态 pending、无人认领、没有依赖；ID 随机生成，排他创建，冲突时重试。 */
    public TaskRecord create(String subject, String description) throws IOException {
        String cleanSubject = validateSubject(subject);
        String cleanDescription = validateDescription(description);
        Files.createDirectories(directory);
        if (taskFiles().size() >= MAX_TASKS) {
            throw new IllegalArgumentException("任务已达到 " + MAX_TASKS + " 个上限，请清理 "
                    + DIRECTORY_NAME + " 中不再需要的任务");
        }
        byte[] random = new byte[4];
        for (int attempt = 0; attempt < 100; attempt++) {
            RANDOM.nextBytes(random);
            TaskRecord task = new TaskRecord("task_" + HexFormat.of().formatHex(random), cleanSubject,
                    cleanDescription, TaskStatus.PENDING, null, List.of());
            try {
                Files.writeString(fileOf(task.id()), toJson(task), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return task;
            } catch (FileAlreadyExistsException e) {
                // 极少发生：换一个 ID 再试。
            }
        }
        throw new IOException("无法分配不重复的任务 ID");
    }

    /** 每次重新读取文件。 */
    public TaskRecord load(String id) throws IOException {
        validateId(id);
        Path file = fileOf(id);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("没有 ID 为 " + id + " 的任务");
        }
        return parse(file, id);
    }

    /** 按 ID 排序列出全部任务；单个文件无效只告警跳过，目录不存在视为没有任务。 */
    public List<TaskRecord> list(Consumer<String> warnings) throws IOException {
        List<TaskRecord> tasks = new ArrayList<>();
        for (Path file : taskFiles()) {
            String fileName = file.getFileName().toString();
            try {
                tasks.add(parse(file, fileName.substring(0, fileName.length() - ".json".length())));
            } catch (IllegalArgumentException | IOException e) {
                warnings.accept("任务> 跳过 " + fileName + "：" + message(e));
            }
        }
        return tasks;
    }

    /**
     * 为 pending 且未认领的任务追加依赖：先校验整次修改（存在、非自身、不成环、数量），全部通过才保存；
     * 已有的依赖重复添加不会产生重复边。
     */
    public TaskRecord addDependencies(String id, List<String> dependencies) throws IOException {
        TaskRecord task = load(id);
        if (task.status() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务 " + id + " 已被认领或完成，只能在 pending 时修改依赖");
        }
        Set<String> merged = new LinkedHashSet<>(task.blockedBy());
        for (String dependency : new LinkedHashSet<>(dependencies)) {
            validateId(dependency);
            if (dependency.equals(id)) {
                throw new IllegalArgumentException("任务不能依赖自身");
            }
            load(dependency);
            if (!merged.contains(dependency) && dependsOn(dependency, id)) {
                throw new IllegalArgumentException("依赖会形成环：" + dependency + " 已直接或间接依赖 " + id);
            }
            merged.add(dependency);
        }
        if (merged.size() > MAX_DEPENDENCIES) {
            throw new IllegalArgumentException("每个任务最多 " + MAX_DEPENDENCIES + " 个依赖");
        }
        TaskRecord updated = task.withBlockedBy(List.copyOf(merged));
        save(updated);
        return updated;
    }

    /** 尚未完成的前置任务；文件缺失或无效的依赖也算未完成。 */
    public List<String> incompleteDependencies(TaskRecord task) throws IOException {
        List<String> incomplete = new ArrayList<>();
        for (String dependency : task.blockedBy()) {
            try {
                if (load(dependency).status() != TaskStatus.COMPLETED) {
                    incomplete.add(dependency);
                }
            } catch (IllegalArgumentException e) {
                incomplete.add(dependency);
            }
        }
        return incomplete;
    }

    /** pending → in_progress：依赖必须全部完成，记录认领者。 */
    public TaskRecord claim(String id, String owner) throws IOException {
        String cleanOwner = validateOwner(owner);
        TaskRecord task = load(id);
        if (task.status() != TaskStatus.PENDING) {
            throw new IllegalArgumentException("任务 " + id + " 状态为 " + task.status().wireName() + "，不能认领");
        }
        List<String> blocked = incompleteDependencies(task);
        if (!blocked.isEmpty()) {
            throw new IllegalArgumentException("任务 " + id + " 仍被未完成的任务阻塞：" + String.join("、", blocked));
        }
        TaskRecord updated = task.withStatus(TaskStatus.IN_PROGRESS, cleanOwner);
        save(updated);
        return updated;
    }

    /** 完成结果：完成后的任务，以及因此刚刚可以开始的下游任务。 */
    public record Completion(TaskRecord task, List<TaskRecord> unblocked) {
    }

    /** in_progress → completed：只有认领者可以完成。 */
    public Completion complete(String id, String owner) throws IOException {
        String cleanOwner = validateOwner(owner);
        TaskRecord task = load(id);
        if (task.status() != TaskStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("任务 " + id + " 状态为 " + task.status().wireName() + "，需要先认领");
        }
        if (!cleanOwner.equals(task.owner())) {
            throw new IllegalArgumentException("任务 " + id + " 由 " + task.owner() + " 认领，不能由 "
                    + cleanOwner + " 完成");
        }
        TaskRecord updated = task.withStatus(TaskStatus.COMPLETED, task.owner());
        save(updated);
        // 下游任务依赖 id，完成前一定不可开始；完成后依赖全部完成的就是刚刚解锁的。
        List<TaskRecord> unblocked = new ArrayList<>();
        for (TaskRecord candidate : list(warning -> { })) {
            if (candidate.status() == TaskStatus.PENDING && candidate.blockedBy().contains(id)
                    && incompleteDependencies(candidate).isEmpty()) {
                unblocked.add(candidate);
            }
        }
        return new Completion(updated, unblocked);
    }

    /** 一行一个任务：标记、ID、标题、状态、认领者与依赖；pending 任务标明可开始或阻塞于哪些依赖。 */
    public String render(List<TaskRecord> tasks) throws IOException {
        if (tasks.isEmpty()) {
            return "没有任务。用 task_create 创建。";
        }
        StringBuilder text = new StringBuilder();
        for (TaskRecord task : tasks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(task.status().marker()).append(' ').append(task.id()).append("：").append(task.subject())
                    .append(" [").append(task.status().wireName()).append(']');
            if (task.owner() != null) {
                text.append(" 认领者=").append(task.owner());
            }
            if (!task.blockedBy().isEmpty()) {
                text.append(" 依赖=").append(String.join(",", task.blockedBy()));
            }
            if (task.status() == TaskStatus.PENDING) {
                List<String> blocked = incompleteDependencies(task);
                text.append(blocked.isEmpty() ? " 可开始" : " 阻塞于=" + String.join(",", blocked));
            }
        }
        return text.toString();
    }

    public static String toJson(TaskRecord task) {
        JSONObject json = new JSONObject(new LinkedHashMap<>());
        json.put("id", task.id());
        json.put("subject", task.subject());
        json.put("description", task.description());
        json.put("status", task.status().wireName());
        json.put("owner", task.owner());
        json.put("blockedBy", task.blockedBy());
        return JSON.toJSONString(json, JSONWriter.Feature.PrettyFormat, JSONWriter.Feature.WriteNulls) + "\n";
    }

    public static void validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("任务 ID 必须形如 task_ 加 8 位小写十六进制，例如 task_0a1b2c3d");
        }
    }

    /** from 是否直接或间接依赖 target；缺失或无效的任务不再展开。 */
    private boolean dependsOn(String from, String target) throws IOException {
        Deque<String> pending = new ArrayDeque<>(List.of(from));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            String current = pending.pop();
            if (current.equals(target)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            try {
                pending.addAll(load(current).blockedBy());
            } catch (IllegalArgumentException e) {
                // 缺失或损坏的任务没有可追踪的依赖。
            }
        }
        return false;
    }

    private List<Path> taskFiles() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("任务路径不是目录：" + directory);
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "task_*.json")) {
            for (Path file : stream) {
                files.add(file);
            }
        }
        files.sort(null);
        return files;
    }

    private void save(TaskRecord task) throws IOException {
        Path temp = Files.createTempFile(directory, task.id() + "-", ".tmp");
        try {
            Files.writeString(temp, toJson(task), StandardCharsets.UTF_8);
            try {
                Files.move(temp, fileOf(task.id()), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, fileOf(task.id()), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path fileOf(String id) {
        return directory.resolve(id + ".json");
    }

    private TaskRecord parse(Path file, String expectedId) throws IOException {
        // 不跟随链接：任务只能是 .tasks 目录中的普通文件。
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("任务 " + expectedId + " 不是普通文件");
        }
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("任务 " + expectedId + " 文件已被删除");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件超过 64 KiB");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("不是有效的 UTF-8");
        }
        JSONObject json;
        try {
            json = JSON.parseObject(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是有效的 JSON 对象");
        }
        if (json == null || !FIELDS.containsAll(json.keySet())) {
            throw new IllegalArgumentException("只能包含 " + String.join("、", FIELDS.stream().sorted().toList()));
        }
        if (!expectedId.equals(json.get("id"))) {
            throw new IllegalArgumentException("id 必须与文件名一致");
        }
        Object description = json.get("description");
        Object owner = json.get("owner");
        if (!(json.get("subject") instanceof String subject) || !(json.get("status") instanceof String status)
                || !(description == null || description instanceof String)
                || !(owner == null || owner instanceof String)
                || !(json.get("blockedBy") instanceof JSONArray blockedBy)) {
            throw new IllegalArgumentException("字段类型错误");
        }
        Set<String> dependencies = new LinkedHashSet<>();
        for (Object dependency : blockedBy) {
            if (!(dependency instanceof String value) || value.equals(expectedId)) {
                throw new IllegalArgumentException("blockedBy 必须是其他任务 ID 的数组");
            }
            validateId(value);
            dependencies.add(value);
        }
        TaskStatus parsed = TaskStatus.parse(status);
        String cleanOwner = owner == null ? null : validateOwner((String) owner);
        if ((parsed == TaskStatus.PENDING) != (cleanOwner == null)) {
            throw new IllegalArgumentException("pending 任务不能有认领者，已认领或完成的任务必须有认领者");
        }
        return new TaskRecord(expectedId, validateSubject(subject), validateDescription((String) description),
                parsed, cleanOwner, List.copyOf(dependencies));
    }

    private static String validateSubject(String subject) {
        if (StrUtil.isBlank(subject) || subject.strip().length() > MAX_SUBJECT_CHARS
                || subject.strip().contains("\n") || subject.strip().contains("\r")) {
            throw new IllegalArgumentException("subject 必须是非空单行文字，最多 " + MAX_SUBJECT_CHARS + " 字符");
        }
        return subject.strip();
    }

    private static String validateDescription(String description) {
        String clean = description == null ? "" : description.strip();
        if (clean.length() > MAX_DESCRIPTION_CHARS) {
            throw new IllegalArgumentException("description 最多 " + MAX_DESCRIPTION_CHARS + " 字符");
        }
        return clean;
    }

    private static String validateOwner(String owner) {
        if (StrUtil.isBlank(owner) || owner.strip().length() > MAX_OWNER_CHARS
                || owner.strip().contains("\n") || owner.strip().contains("\r")) {
            throw new IllegalArgumentException("owner 必须是非空单行文字，最多 " + MAX_OWNER_CHARS + " 字符");
        }
        return owner.strip();
    }

    private static String message(Exception e) {
        return e instanceof IllegalArgumentException ? e.getMessage()
                : "读取失败（" + e.getClass().getSimpleName() + "）";
    }
}
