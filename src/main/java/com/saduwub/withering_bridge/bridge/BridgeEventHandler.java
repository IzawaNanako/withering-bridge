package com.saduwub.withering_bridge.bridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;

public class BridgeEventHandler {
    public static MinecraftServer currentServer;

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            currentServer = server;
            sendSystemMessage("**Server started!**", "start");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(_ -> {
            BridgeWebSocketClient client = BridgeWebSocketClient.getInstance();
            if (client != null) {
                BridgePayloads.McSystemData data = new BridgePayloads.McSystemData("**Server stopped!**", "stop");
                client.sendPayload("system_mc_to_discord", data);

                client.stop();
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, _, _) -> {
            String name = handler.player.getName().getString();
            sendSystemMessage("**" + name + " joined the server**", "join");
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, _) -> {
            String name = handler.player.getName().getString();
            sendSystemMessage("**" + name + " left the server**", "leave");
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, _) -> {
            String text = message.signedContent();
            String username = sender.getName().getString();
            String uuid = sender.getUUID().toString();

            BridgePayloads.McChatData chatData = new BridgePayloads.McChatData(username, uuid, text);

            if (BridgeWebSocketClient.getInstance() != null) {
                BridgeWebSocketClient.getInstance().sendPayload("chat_mc_to_discord", chatData);
            }
        });

        ServerMessageEvents.COMMAND_MESSAGE.register((message, source, _) -> {
            if (source.isPlayer()) {
                return;
            }

            if (BridgeWebSocketClient.getInstance() != null) {
                BridgePayloads.McSystemData data = new BridgePayloads.McSystemData(message.signedContent(), "console");
                BridgeWebSocketClient.getInstance().sendPayload("system_mc_to_discord", data);
            }
        });
    }

    private static void sendSystemMessage(String message, String eventType) {
        if (BridgeWebSocketClient.getInstance() != null) {
            BridgePayloads.McSystemData systemData = new BridgePayloads.McSystemData(message, eventType);
            BridgeWebSocketClient.getInstance().sendPayload("system_mc_to_discord", systemData);
        }
    }
}
