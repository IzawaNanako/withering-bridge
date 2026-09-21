package com.saduwub.withering_bridge.bridge;

import com.google.gson.JsonElement;
import java.util.List;

public class BridgePayloads {
    public record BaseMessage(String type, JsonElement data) {}
    public record AuthData(String secret) {}
    public record McChatData(String username, String uuid, String message) {}
    public record McSystemData(String message, String eventType) {}
    public record DiscordChatData(
            String username,
            String message,
            List<String> mentions,
            List<String> attachments,
            boolean isEveryonePing,
            boolean renderMarkdown
    ) {}
}
