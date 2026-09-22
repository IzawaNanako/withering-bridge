package com.saduwub.withering_bridge.bridge;

import com.google.gson.Gson;
import com.saduwub.withering_bridge.WitheringBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class BridgeWebSocketClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static BridgeWebSocketClient instance;
    private final StringBuilder textBuffer = new StringBuilder();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WitheringBridge-WS");
        thread.setDaemon(true);
        return thread;
    });
    private WebSocket webSocket;
    private boolean isConnecting = false;

    public static void start() {
        if (instance == null) {
            instance = new BridgeWebSocketClient();
            instance.connect();
        }
    }

    public static BridgeWebSocketClient getInstance() {
        return instance;
    }

    @SuppressWarnings("resource")
    private void connect() {
        if (isConnecting || (webSocket != null && !webSocket.isInputClosed())) {
            return;
        }
        isConnecting = true;

        BridgeConfig config = BridgeConfig.get();

        try {
            HttpClient client = HttpClient.newHttpClient();
            client.newWebSocketBuilder()
                    .buildAsync(URI.create(config.wsUri), this)
                    .whenComplete((ws, error) -> {
                        isConnecting = false;
                        if (error != null) {
                            scheduleReconnect();
                        } else {
                            this.webSocket = ws;
                        }
                    });
        } catch (Exception e) {
            isConnecting = false;
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        scheduler.schedule(this::connect, 5, TimeUnit.SECONDS);
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;

        BridgeConfig config = BridgeConfig.get();
        BridgePayloads.AuthData authData = new BridgePayloads.AuthData(config.wsSecret);

        sendPayload("auth", authData);

        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        textBuffer.append(data);

        if (last) {
            String fullMessage = textBuffer.toString();
            textBuffer.setLength(0);

            try {
                BridgePayloads.BaseMessage base = GSON.fromJson(fullMessage, BridgePayloads.BaseMessage.class);

                if ("chat_discord_to_mc".equals(base.type())) {
                    BridgePayloads.DiscordChatData chatData = GSON.fromJson(base.data(), BridgePayloads.DiscordChatData.class);
                    processDiscordMessage(chatData);
                }
            } catch (Exception e) {
                WitheringBridge.LOGGER.error("[Withering Bridge] Failed to parse incoming Discord message: {}", e.getMessage());
            }
        }

        webSocket.request(1);
        return null;
    }

    private void processDiscordMessage(BridgePayloads.DiscordChatData data) {
        MinecraftServer server = BridgeEventHandler.currentServer;
        if (server == null) {
            return;
        }

        server.execute(() -> {
            MutableComponent prefix = Component.literal("[Discord] ").withStyle(ChatFormatting.BLUE);
            MutableComponent name = Component.literal("<" + data.username() + "> ");

            if (data.roleColor() != null && data.roleColor() != 0) {
                name.withStyle(style -> style.withColor(TextColor.fromRgb(data.roleColor())));
            } else {
                name.withStyle(ChatFormatting.GRAY);
            }

            String safeMessage = truncateDiscordMessage(data.message());

            MutableComponent content = parseMessageContent(safeMessage, data.renderMarkdown());

            for (String url : data.attachments()) {
                ClickEvent.OpenUrl clickEvent = createSafeOpenUrl(url);
                if (clickEvent != null) {
                    content.append(Component.literal(" [Attachment]").withStyle(style ->
                            style.withColor(ChatFormatting.AQUA)
                                    .withUnderlined(true)
                                    .withClickEvent(clickEvent)
                    ));
                }
            }

            MutableComponent fullMessage = prefix.append(name).append(content);

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean isPinged = data.isEveryonePing() || data.mentions().contains(player.getName().getString());

                if (isPinged) {
                    player.playSound(SoundEvents.NOTE_BLOCK_PLING.value(), 1.0f, 1.0f);
                }

                player.sendSystemMessage(fullMessage);
            }
        });
    }

    private String truncateDiscordMessage(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        int maxLength = 256;
        int maxLines = 4;

        String[] lines = text.split("\n");
        if (lines.length > maxLines) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxLines; i++) {
                sb.append(lines[i]).append("\n");
            }
            text = sb.toString().trim() + " ...";
        }

        if (text.length() > maxLength) {
            if (Character.isHighSurrogate(text.charAt(maxLength - 1))) {
                text = text.substring(0, maxLength - 1) + "...";
            } else {
                text = text.substring(0, maxLength) + "...";
            }
        }

        return text;
    }

    private MutableComponent parseMessageContent(String text, boolean renderMarkdown) {
        MutableComponent root = Component.empty();

        String[] parts = text.split("(?<= )|(?= )");

        for (String part : parts) {
            ClickEvent.OpenUrl clickEvent = createSafeOpenUrl(part);
            if ((part.startsWith("http://") || part.startsWith("https://")) && clickEvent != null) {
                root.append(Component.literal(part).withStyle(style ->
                        style.withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(clickEvent)
                ));
            } else {
                MutableComponent textNode = Component.literal(part).withStyle(ChatFormatting.WHITE);

                if (renderMarkdown) {
                    if (part.startsWith("**") && part.endsWith("**") && part.length() > 4) {
                        textNode = Component.literal(part.substring(2, part.length() - 2)).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
                    } else if ((part.startsWith("*") && part.endsWith("*") || part.startsWith("_") && part.endsWith("_")) && part.length() > 2) {
                        textNode = Component.literal(part.substring(1, part.length() - 1)).withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC);
                    }
                }

                root.append(textNode);
            }
        }

        return root;
    }

    private static ClickEvent.OpenUrl createSafeOpenUrl(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return new ClickEvent.OpenUrl(uri);
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        if (this.webSocket != null) {
            this.webSocket.abort();
            this.webSocket = null;
        }

        scheduleReconnect();

        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        if (this.webSocket != null) {
            this.webSocket.abort();
            this.webSocket = null;
        }

        scheduleReconnect();
    }

    public void sendPayload(String type, Object data) {
        if (webSocket != null) {
            String json = GSON.toJson(new BridgePayloads.BaseMessage(type, GSON.toJsonTree(data)));
            webSocket.sendText(json, true);
        }
    }
}
