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
        return getMessages(systemPrompt, Config.maxHistory);
    }

    public synchronized List<ChatMessage> getMessages(String systemPrompt, int maxHistory) {
        List<ChatMessage> result = new ArrayList<>();
        result.add(new ChatMessage("system", systemPrompt));
        int maxMessages = maxHistory * 2;
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

    /** Returns a snapshot of the full history for persistence. */
    public synchronized List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    /** Replaces the history (used when restoring a persisted session). */
    public synchronized void loadHistory(List<ChatMessage> messages) {
        history.clear();
        history.addAll(messages);
    }
}
