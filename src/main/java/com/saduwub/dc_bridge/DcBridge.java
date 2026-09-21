package com.saduwub.dc_bridge;

import com.saduwub.dc_bridge.bridge.BridgeConfig;
import com.saduwub.dc_bridge.bridge.BridgeEventHandler;
import com.saduwub.dc_bridge.bridge.BridgeWebSocketClient;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DcBridge implements DedicatedServerModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("dc-bridge");

	@Override
	public void onInitializeServer() {
		LOGGER.info("[Discord Bridge] Initializing...");

		BridgeConfig config = BridgeConfig.get();

		if (config.enableBridge) {
			LOGGER.info("[Discord Bridge] Mod enabled. Will connect to: {}", config.wsUri);
			BridgeWebSocketClient.start();
			BridgeEventHandler.register();
		}
	}
}
