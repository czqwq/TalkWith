package com.czqwq.talkwith.ai;

import java.util.ArrayList;
import java.util.List;

import com.czqwq.talkwith.Config;

public class ChatSession {

    private final List<ChatMessage> history = new ArrayList<>();

    public synchronized void addMessage(String role, String content) {
        history.add(new ChatMessage(role, content));
    }

    public synchronized List<ChatMessage> getMessages(String systemPrompt) {
        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage("system", systemPrompt));
        int maxPairs = Config.maxHistory;
        int maxMessages = maxPairs * 2;
        int start = Math.max(0, history.size() - maxMessages);
        for (int i = start; i < history.size(); i++) {
            result.add(history.get(i));
        }
        return result;
    }

    public synchronized void clear() {
        history.clear();
    }

    public synchronized int size() {
        return history.size();
    }
}
