package com.saduwub.withering_bridge.mixin;

import com.saduwub.withering_bridge.bridge.BridgePayloads;
import com.saduwub.withering_bridge.bridge.BridgeWebSocketClient;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.gamerules.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {
    @Shadow
    private ServerPlayer player;

    @Shadow
    public abstract AdvancementProgress getOrStartProgress(AdvancementHolder advancement);

    @SuppressWarnings("resource")
    @Inject(method = "award", at = @At("RETURN"))
    private void onAdvancementAwarded(AdvancementHolder holder, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            AdvancementProgress progress = this.getOrStartProgress(holder);
            DisplayInfo displayInfo = holder.value().display().orElse(null);

            if (displayInfo != null && displayInfo.announceToChat() && progress.isDone() && this.player.level().getGameRules().get(GameRules.SHOW_ADVANCEMENT_MESSAGES)) {
                String playerName = this.player.getName().getString();
                String title = displayInfo.title().getString();
                String description = displayInfo.description().getString();
                String message = "**" + playerName + " has made the advancement [" + title + "]**\n__ - " + description + "__";

                if (BridgeWebSocketClient.getInstance() != null) {
                    BridgePayloads.McSystemData data = new BridgePayloads.McSystemData(message, "advancement");
                    BridgeWebSocketClient.getInstance().sendPayload("system_mc_to_discord", data);
                }
            }
        }
    }
}
