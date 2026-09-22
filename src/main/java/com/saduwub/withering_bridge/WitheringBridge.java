package com.saduwub.withering_bridge;

import com.saduwub.withering_bridge.bridge.BridgeConfig;
import com.saduwub.withering_bridge.bridge.BridgeEventHandler;
import com.saduwub.withering_bridge.bridge.BridgeWebSocketClient;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WitheringBridge implements DedicatedServerModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("withering-bridge");

    @Override
    public void onInitializeServer() {
        LOGGER.info("[Withering Bridge] Initializing...");

        BridgeConfig config = BridgeConfig.get();

        if (config.enableBridge) {
            LOGGER.info("[Withering Bridge] Mod enabled. Will connect to: {}", config.wsUri);
            BridgeWebSocketClient.start();
            BridgeEventHandler.register();
        }
    }
}
