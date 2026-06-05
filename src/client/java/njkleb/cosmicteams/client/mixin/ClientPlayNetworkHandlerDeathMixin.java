package njkleb.cosmicteams.client.mixin;

import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import njkleb.cosmicteams.client.DeathHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Injects into {@link ClientPlayNetworkHandler#onHealthUpdate} to provide
 * reliable, packet-level death detection for the CosmicTeams death ping feature.
 *
 * <h2>Why a mixin instead of tick polling</h2>
 * Polling {@code mc.player.getHealth()} or {@code mc.player.isDead()} in
 * {@code ClientTickEvents.END_CLIENT_TICK} misses deaths on servers that:
 * <ul>
 *   <li>Immediately respawn the player — the entity is replaced with full health
 *       before the next tick fires, so zero-health is never observed.</li>
 *   <li>Immediately world-transfer the player on death — the world change
 *       processing runs before the next tick, resetting player state.</li>
 * </ul>
 *
 * <h2>Reliability guarantee</h2>
 * This injection fires synchronously on the game thread at the {@code HEAD} of
 * {@code onHealthUpdate}, immediately after the server's
 * {@link HealthUpdateS2CPacket} is dequeued and before vanilla has applied the
 * new health value to the player entity.  Crucially, subsequent packets (respawn,
 * world transfer) cannot be processed until this handler returns, because the
 * Minecraft packet pipeline is sequential.  The player position read in
 * {@link DeathHandler#onHealthUpdate} therefore reflects the actual
 * death location even if a respawn follows in the same packet batch.
 *
 * <h2>Logic</h2>
 * All decision-making is delegated to {@link DeathHandler} to keep
 * this class a thin adapter.
 *
 * <h2>Registration</h2>
 * Add {@code "ClientPlayNetworkHandlerDeathMixin"} to the {@code "client"} array
 * in {@code cosmicteams.mixins.json}.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerDeathMixin {

    @Inject(method = "onHealthUpdate", at = @At("HEAD"))
    private void cosmicteams_onHealthUpdate(HealthUpdateS2CPacket packet, CallbackInfo ci) {
        DeathHandler.onHealthUpdate(packet.getHealth());
    }
}