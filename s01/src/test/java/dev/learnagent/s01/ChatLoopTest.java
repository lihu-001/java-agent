package dev.learnagent.s01;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatLoopTest {
    @Test
    void retainsSuccessfulTurnsAndSkipsBlankInput() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("\n我叫小李\n我叫什么？\nexit\n不会发送\n", messages -> {
            requests.add(List.copyOf(messages));
            return requests.size() == 1 ? "你好，小李" : "你叫小李";
        });

        assertEquals(2, requests.size());
        assertEquals(List.of("system", "user", "assistant", "user"),
                requests.get(1).stream().map(ChatMessage::role).toList());
        assertEquals("我叫小李", requests.get(1).get(1).content());
        assertEquals("你好，小李", requests.get(1).get(2).content());
        assertTrue(output.contains("你叫小李"));
    }

    @Test
    void failedTurnIsNotAddedToHistoryAndNextInputStillWorks() throws IOException {
        List<List<ChatMessage>> requests = new ArrayList<>();
        String output = run("失败的问题\n新的问题\n", messages -> {
            requests.add(List.copyOf(messages));
            if (requests.size() == 1) {
                throw new IOException("模型接口返回 HTTP 401");
            }
            return "恢复成功";
        });

        assertEquals(2, requests.get(1).size());
        assertEquals("新的问题", requests.get(1).get(1).content());
        assertTrue(output.contains("HTTP 401"));
        assertTrue(output.contains("恢复成功"));
    }

    @Test
    void quitAndEndOfInputDoNotCallModel() throws IOException {
        ModelClient unexpected = messages -> {
            fail("退出时不应请求模型");
            return "";
        };
        run("quit\n", unexpected);
        run("", unexpected);
    }

    @Test
    void interruptionStopsLoopAndRestoresInterruptFlag() throws IOException {
        try {
            String output = run("你好\n不应继续\n", messages -> {
                throw new InterruptedException();
            });
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(output.contains("中断"));
        } finally {
            Thread.interrupted();
        }
    }

    private String run(String input, ModelClient client) throws IOException {
        StringWriter output = new StringWriter();
        new ChatLoop(client).run(new BufferedReader(new StringReader(input)), new PrintWriter(output, true));
        return output.toString();
    }
}
