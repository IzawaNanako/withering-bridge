package com.saduwub.withering_bridge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.saduwub.withering_bridge.WitheringBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Set;

public class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(FabricLoader.getInstance().getConfigDir().toFile(), "withering-bridge.json");
    private static BridgeConfig instance;

    public boolean enableBridge = true;
    public String wsUri = "ws://localhost:5565";
    public String wsSecret = "";

    private static final Set<String> PLACEHOLDERS = Set.of(
            "", "SECRET", "VERYSECRET"
    );

    public static BridgeConfig get() {
        if (instance == null) {
            instance = loadOrCreate();
        }
        return instance;
    }

    private static BridgeConfig loadOrCreate() {
        BridgeConfig config = new BridgeConfig();
        boolean needsSave = false;

        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                config = GSON.fromJson(reader, BridgeConfig.class);
                if (config == null) {
                    config = new BridgeConfig();
                }
            } catch (IOException e) {
                WitheringBridge.LOGGER.warn("[Withering Bridge] Could not read config file, generating default.");
            }
        } else {
            needsSave = true;
        }

        if (isPlaceholder(config.wsSecret)) {
            config.wsSecret = generateSecret();
            needsSave = true;
            WitheringBridge.LOGGER.info("[Withering Bridge] Generated a new secret. Check config/dc-bridge.json to view it.");
        }

        if (needsSave) {
            save(config);
        }

        return config;
    }

    private static boolean isPlaceholder(String secret) {
        return secret == null || PLACEHOLDERS.contains(secret.trim().toUpperCase());
    }

    private static String generateSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void save(BridgeConfig config) {
        File parentDir = CONFIG_FILE.getParentFile();

        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdir()) {
                WitheringBridge.LOGGER.error("[Withering Bridge] Failed to create config directory.");
                return;
            }
        }

        try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
            GSON.toJson(config, writer);
        } catch (IOException e) {
            WitheringBridge.LOGGER.error("[Withering Bridge] Failed to save config file.", e);
        }
    }
}
