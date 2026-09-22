package com.saduwub.withering_bridge.bridge;

import com.google.gson.JsonElement;

import java.util.List;

public class BridgePayloads {
    public record BaseMessage(String type, JsonElement data) {}

    public record SpanData(String text, Boolean bold, Boolean italic, Boolean underline, Boolean strikethrough, Boolean spoiler, Boolean code, String url, String hoverText) {}

    public record ReplyData(String author, String preview, String hoverText) {}

    public record DiscordChatData(String username, String message, List<SpanData> spans, List<String> mentions, List<String> attachments, boolean isEveryonePing, boolean renderMarkdown, Integer roleColor, ReplyData replyData) {}

    public record AuthData(String secret) {}

    public record McChatData(String username, String uuid, String message) {}

    public record McSystemData(String message, String eventType) {}
}
