package com.czqwq.talkwith.ai;

public class ChatMessage {

    public final String role;
    public final String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
