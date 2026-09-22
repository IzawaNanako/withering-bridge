package com.saduwub.withering_bridge.bridge;

import com.google.gson.Gson;
import com.saduwub.withering_bridge.WitheringBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
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

    @SuppressWarnings("resource")
    private void connect() {
        if (isConnecting || (webSocket != null && !webSocket.isInputClosed())) {
            return;
        }
        isConnecting = true;

        BridgeConfig config = BridgeConfig.get();

        try {
            HttpClient client = HttpClient.newHttpClient();
            client.newWebSocketBuilder().buildAsync(URI.create(config.wsUri), this).whenComplete((ws, error) -> {
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

            MutableComponent content = buildMessageComponent(data);

            for (String url : data.attachments()) {
                ClickEvent.OpenUrl clickEvent = createSafeOpenUrl(url);
                if (clickEvent != null) {
                    content.append(Component.literal(" [Attachment]").withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true).withClickEvent(clickEvent)));
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

    private MutableComponent buildMessageComponent(BridgePayloads.DiscordChatData data) {
        MutableComponent root = Component.empty();

        if (data.spans() != null && !data.spans().isEmpty()) {
            for (BridgePayloads.SpanData span : data.spans()) {
                if (span.text() == null || span.text().isEmpty()) {
                    continue;
                }

                MutableComponent part = Component.literal(span.text());
                Style style = Style.EMPTY.withColor(ChatFormatting.WHITE);

                if (Boolean.TRUE.equals(span.bold())) {
                    style = style.withBold(true);
                }
                if (Boolean.TRUE.equals(span.italic())) {
                    style = style.withItalic(true);
                }
                if (Boolean.TRUE.equals(span.underline())) {
                    style = style.withUnderlined(true);
                }
                if (Boolean.TRUE.equals(span.strikethrough())) {
                    style = style.withStrikethrough(true);
                }

                if (Boolean.TRUE.equals(span.code())) {
                    style = style.withColor(ChatFormatting.GRAY).withItalic(false);
                }

                if (Boolean.TRUE.equals(span.spoiler())) {
                    HoverEvent hover = new HoverEvent.ShowText(
                            Component.literal("Spoiler: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(span.text()).withStyle(ChatFormatting.WHITE))
                    );
                    style = style.withObfuscated(true).withHoverEvent(hover);
                }

                if (span.url() != null && !span.url().isEmpty()) {
                    try {
                        URI uri = URI.create(span.url());
                        style = style.withColor(ChatFormatting.BLUE)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent.OpenUrl(uri));
                    } catch (Exception ignored) {}
                }

                root.append(part.withStyle(style));
            }

            return root;
        }
        root.append(Component.literal(data.message()).withStyle(ChatFormatting.WHITE));
        return root;
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

    public void stop() {
        scheduler.shutdownNow();

        if (webSocket != null && !webSocket.isOutputClosed()) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Server shutting down").toCompletableFuture().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                webSocket.abort();
            } finally {
                webSocket = null;
            }
        }
    }
}
