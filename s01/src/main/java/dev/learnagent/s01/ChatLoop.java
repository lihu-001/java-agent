package dev.learnagent.s01;

import cn.hutool.core.util.StrUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;

public final class ChatLoop {
    private final ModelClient client;

    public ChatLoop(ModelClient client) {
        this.client = client;
    }

    public void run(BufferedReader input, PrintWriter output) throws IOException {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", "你是一个友好的助手，请用中文回答。"));
        output.println("对话已启动，输入 exit 或 quit 退出。每行输入发送一次请求。");
        while (true) {
            output.print("你> ");
            output.flush();
            String line = input.readLine();
            if (line == null || "exit".equalsIgnoreCase(line.strip()) || "quit".equalsIgnoreCase(line.strip())) {
                output.println("对话已结束。");
                output.flush();
                return;
            }
            if (StrUtil.isBlank(line)) {
                continue;
            }

            // 使用历史副本发起请求，成功后再用完整问答更新历史。
            List<ChatMessage> request = new ArrayList<>(history);
            request.add(new ChatMessage("user", line));
            try {
                String answer = client.complete(List.copyOf(request));
                request.add(new ChatMessage("assistant", answer));
                history = request;
                output.println("助手> " + answer);
            } catch (HttpTimeoutException e) {
                output.println("请求超时，本轮未保存，请重新输入。");
            } catch (IOException e) {
                output.println("请求失败：" + e.getMessage() + "；本轮未保存，请重新输入。");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output.println("请求已中断，对话结束。");
                output.flush();
                return;
            }
            output.flush();
        }
    }
}
