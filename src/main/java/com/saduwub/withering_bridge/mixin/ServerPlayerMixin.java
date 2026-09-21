package com.saduwub.withering_bridge.mixin;

import com.saduwub.withering_bridge.bridge.BridgePayloads;
import com.saduwub.withering_bridge.bridge.BridgeWebSocketClient;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "die", at = @At("HEAD"))
    private void onPlayerDie(DamageSource source, CallbackInfo ci) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        String deathMessage = player.getCombatTracker().getDeathMessage().getString();

        if (BridgeWebSocketClient.getInstance() != null) {
            BridgePayloads.McSystemData data = new BridgePayloads.McSystemData("**" + deathMessage + "**", "death");
            BridgeWebSocketClient.getInstance().sendPayload("system_mc_to_discord", data);
        }
    }
}
