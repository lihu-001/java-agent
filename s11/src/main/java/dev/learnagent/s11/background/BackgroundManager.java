package dev.learnagent.s11.background;

import dev.learnagent.s11.tool.AgentTool;

import com.alibaba.fastjson2.JSON;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 在守护线程中执行 Shell 命令，主循环不等待。完成后把通知放入队列，由 ChatLoop 在下一次模型请求前取走；
 * 同时立即在终端提示。任务只在本次会话内存中，退出时取消仍在运行的命令。
 */
public final class BackgroundManager {
    public static final int MAX_RUNNING = 4;
    public static final int MAX_TASKS = 20;
    public static final int MAX_PREVIEW_CHARS = 2000;
    public static final String NOTIFICATION_START = "<background-results>";
    public static final String NOTIFICATION_END = "</background-results>";

    private static final class Entry {
        private final String id;
        private final String command;
        private final long started = System.nanoTime();
        private volatile BackgroundStatus status = BackgroundStatus.RUNNING;
        private volatile String result;
        private volatile long elapsedMillis = -1;
        private Future<?> future;

        private Entry(String id, String command) {
            this.id = id;
            this.command = command;
        }

        private BackgroundTask snapshot() {
            long elapsed = elapsedMillis >= 0 ? elapsedMillis : (System.nanoTime() - started) / 1_000_000;
            return new BackgroundTask(id, command, status, result, elapsed);
        }
    }

    private final AgentTool shell;
    private final Consumer<String> terminal;
    private final Map<String, Entry> tasks = new LinkedHashMap<>();
    private final ConcurrentLinkedDeque<String> notifications = new ConcurrentLinkedDeque<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "background-task");
        thread.setDaemon(true);
        return thread;
    });
    private int nextId = 1;
    private boolean closed;

    /** shell 为后台专用实例（超时通常比前台长）；terminal 接收完成提示，可能在后台线程调用。 */
    public BackgroundManager(AgentTool shell, Consumer<String> terminal) {
        this.shell = Objects.requireNonNull(shell);
        this.terminal = Objects.requireNonNull(terminal);
    }

    public synchronized BackgroundTask start(String command) {
        if (closed) {
            throw new IllegalArgumentException("会话正在结束，不能再启动后台任务。");
        }
        long running = tasks.values().stream().filter(e -> e.status == BackgroundStatus.RUNNING).count();
        if (running >= MAX_RUNNING) {
            throw new IllegalArgumentException("同时运行的后台任务最多 " + MAX_RUNNING + " 个，请等待完成后再启动。");
        }
        if (tasks.size() >= MAX_TASKS) {
            throw new IllegalArgumentException("本次会话最多启动 " + MAX_TASKS + " 个后台任务。");
        }
        Entry entry = new Entry("bg_" + nextId++, command);
        tasks.put(entry.id, entry);
        entry.future = executor.submit(() -> run(entry));
        return entry.snapshot();
    }

    private void run(Entry entry) {
        String result;
        BackgroundStatus status;
        try {
            result = shell.execute(JSON.toJSONString(Map.of("command", entry.command)));
            status = statusOf(result);
        } catch (InterruptedException e) {
            // ShellTool 在中断时会终止进程树。
            result = "已取消：会话结束或任务被中断，命令可能已部分执行。";
            status = BackgroundStatus.CANCELLED;
        } catch (IOException | RuntimeException e) {
            result = "工具错误：后台命令启动或读取失败（" + e.getClass().getSimpleName() + "）。";
            status = BackgroundStatus.FAILED;
        }
        finish(entry, status, result);
    }

    private void finish(Entry entry, BackgroundStatus status, String result) {
        synchronized (this) {
            if (entry.status != BackgroundStatus.RUNNING) {
                return;
            }
            entry.result = result;
            entry.elapsedMillis = (System.nanoTime() - entry.started) / 1_000_000;
            entry.status = status;
            if (status == BackgroundStatus.CANCELLED) {
                return;
            }
        }
        notifications.add(notification(entry.snapshot()));
        terminal.accept("后台> " + entry.id + " " + status.label() + "（" + entry.elapsedMillis
                + " ms）：" + entry.command + "，下一次请求模型时通知。");
    }

    static BackgroundStatus statusOf(String result) {
        if (result.startsWith("退出码：0\n") || "退出码：0".equals(result)) {
            return BackgroundStatus.COMPLETED;
        }
        return result.startsWith("工具错误：命令超时") ? BackgroundStatus.TIMED_OUT : BackgroundStatus.FAILED;
    }

    private static String notification(BackgroundTask task) {
        return "[" + task.id() + "] " + task.status().label() + "，耗时 " + task.elapsedMillis() + " ms，命令："
                + task.command() + "\n" + preview(task.result());
    }

    static String preview(String result) {
        String text = result.strip();
        return text.length() <= MAX_PREVIEW_CHARS ? text
                : text.substring(0, MAX_PREVIEW_CHARS) + "\n…（已截断，用 background_check 查看完整输出）";
    }

    /** 取走全部未通知的完成结果；每条结果只交付一次。 */
    public List<String> takeNotifications() {
        List<String> items = new ArrayList<>();
        for (String item; (item = notifications.poll()) != null; ) {
            items.add(item);
        }
        return items;
    }

    /** 本轮请求失败且消息未保存时放回队首，下次请求再交付，保持原顺序。 */
    public void requeue(List<String> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            notifications.addFirst(items.get(i));
        }
    }

    public static String render(List<String> items) {
        return NOTIFICATION_START + "\n后台任务已结束：\n" + String.join("\n\n", items) + "\n" + NOTIFICATION_END;
    }

    public synchronized BackgroundTask get(String id) {
        Entry entry = tasks.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("没有后台任务 " + id + "。");
        }
        return entry.snapshot();
    }

    public synchronized List<BackgroundTask> list() {
        return tasks.values().stream().map(Entry::snapshot).toList();
    }

    /** 会话结束时调用：取消仍在运行的任务并等待其结束，返回取消的数量。 */
    public int shutdown() {
        List<Entry> running;
        synchronized (this) {
            closed = true;
            running = tasks.values().stream().filter(e -> e.status == BackgroundStatus.RUNNING).toList();
        }
        for (Entry entry : running) {
            entry.future.cancel(true);
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (Entry entry : running) {
            finish(entry, BackgroundStatus.CANCELLED, "已取消：会话结束，命令可能已部分执行。");
        }
        return running.size();
    }
}
