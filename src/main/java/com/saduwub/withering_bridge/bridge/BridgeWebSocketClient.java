package com.saduwub.withering_bridge.bridge;

import com.google.gson.Gson;
import com.saduwub.withering_bridge.WitheringBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BridgeWebSocketClient implements WebSocket.Listener {
    private static final Gson GSON = new Gson();
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");
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

            String safeMessage = truncateDiscordMessage(data.message());

            MutableComponent content = parseMessageContent(safeMessage, data.renderMarkdown());

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
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }

        if (!renderMarkdown) {
            return parseUrlsOnly(text);
        }

        return parseTokens(text, Style.EMPTY.withColor(ChatFormatting.WHITE));
    }

    private MutableComponent parseUrlsOnly(String text) {
        MutableComponent root = Component.empty();
        Matcher matcher = URL_PATTERN.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                root.append(Component.literal(text.substring(lastEnd, matcher.start())).withStyle(ChatFormatting.WHITE));
            }
            String url = matcher.group();
            root.append(createUrlComponent(url));
            lastEnd = matcher.end();
        }

        if (lastEnd < text.length()) {
            root.append(Component.literal(text.substring(lastEnd)).withStyle(ChatFormatting.WHITE));
        }

        return root;
    }

    private MutableComponent parseTokens(String input, Style currentStyle) {
        MutableComponent root = Component.empty();
        int len = input.length();
        int i = 0;
        StringBuilder buffer = new StringBuilder();

        while (i < len) {
            if (input.charAt(i) == '\\' && i + 1 < len) {
                buffer.append(input.charAt(i + 1));
                i += 2;
                continue;
            }

            if (input.startsWith("http://", i) || input.startsWith("https://", i)) {
                Matcher matcher = URL_PATTERN.matcher(input.substring(i));
                if (matcher.find() && matcher.start() == 0) {
                    flushBuffer(root, buffer, currentStyle);
                    String url = matcher.group();
                    root.append(createUrlComponent(url));
                    i += url.length();
                    continue;
                }
            }

            if (input.charAt(i) == '`') {
                int close = input.indexOf('`', i + 1);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String codeText = input.substring(i + 1, close);
                    Style codeStyle = currentStyle.withColor(ChatFormatting.GRAY).withItalic(false);
                    root.append(Component.literal(codeText).withStyle(codeStyle));
                    i = close + 1;
                    continue;
                }
            }

            if (input.startsWith("***", i)) {
                int close = input.indexOf("***", i + 3);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 3, close);
                    root.append(parseTokens(inner, currentStyle.withBold(true).withItalic(true)));
                    i = close + 3;
                    continue;
                }
            }

            if (input.startsWith("**", i)) {
                int close = input.indexOf("**", i + 2);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 2, close);
                    root.append(parseTokens(inner, currentStyle.withBold(true)));
                    i = close + 2;
                    continue;
                }
            }

            if (input.startsWith("__", i)) {
                int close = input.indexOf("__", i + 2);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 2, close);
                    root.append(parseTokens(inner, currentStyle.withUnderlined(true)));
                    i = close + 2;
                    continue;
                }
            }

            if (input.startsWith("~~", i)) {
                int close = input.indexOf("~~", i + 2);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 2, close);
                    root.append(parseTokens(inner, currentStyle.withStrikethrough(true)));
                    i = close + 2;
                    continue;
                }
            }

            if (input.startsWith("||", i)) {
                int close = input.indexOf("||", i + 2);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 2, close);

                    HoverEvent spoilerHover = new HoverEvent.ShowText(
                            Component.literal("Spoiler: ").withStyle(ChatFormatting.GRAY).append(Component.literal(inner).withStyle(ChatFormatting.WHITE)));

                    Style spoilerStyle = currentStyle.withObfuscated(true).withHoverEvent(spoilerHover);

                    root.append(parseTokens(inner, spoilerStyle));
                    i = close + 2;
                    continue;
                }
            }

            char c = input.charAt(i);
            if (c == '*' || c == '_') {
                int close = input.indexOf(c, i + 1);
                if (close != -1) {
                    flushBuffer(root, buffer, currentStyle);
                    String inner = input.substring(i + 1, close);
                    root.append(parseTokens(inner, currentStyle.withItalic(true)));
                    i = close + 1;
                    continue;
                }
            }

            buffer.append(input.charAt(i));
            i++;
        }

        flushBuffer(root, buffer, currentStyle);
        return root;
    }

    private void flushBuffer(MutableComponent root, StringBuilder buffer, Style style) {
        if (!buffer.isEmpty()) {
            root.append(Component.literal(buffer.toString()).withStyle(style));
            buffer.setLength(0);
        }
    }

    private MutableComponent createUrlComponent(String url) {
        MutableComponent comp = Component.literal(url);
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                return comp.withStyle(style -> style.withColor(ChatFormatting.BLUE).withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(uri)));
            }
        } catch (Exception ignored) {}

        return comp.withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE);
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
