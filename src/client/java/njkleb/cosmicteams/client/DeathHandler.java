package njkleb.cosmicteams.client;

import net.minecraft.client.MinecraftClient;

/**
 * Centralised handler for client-side player death detection.
 *
 * <h2>Detection strategy</h2>
 * Death is detected by injecting into
 * {@code ClientPlayNetworkHandler.onHealthUpdate} via
 * {@code ClientPlayNetworkHandlerDeathMixin}.  This fires synchronously on the
 * game thread the instant the server's {@code HealthUpdateS2CPacket} with
 * {@code health == 0} is processed — before any subsequent respawn or
 * world-transfer packet can be handled.  This makes it reliable even on servers
 * that immediately respawn the player or transfer them to a new world upon death,
 * both of which would defeat tick-based health polling.
 *
 * <h2>Double-fire guard</h2>
 * {@link #deathPingFired} prevents multiple pings if the server sends more than
 * one health-zero update for the same death.  It resets as soon as a positive
 * health value is received (i.e. after respawn), so subsequent deaths always
 * fire normally.
 *
 * <h2>Call sites</h2>
 * <ul>
 *   <li>{@link #onHealthUpdate(float)} — called by the mixin on every health packet.</li>
 *   <li>{@link #onDisconnect()} — called by {@code CosmicTeamsClient} on world
 *       disconnect to reset state for the next session.</li>
 * </ul>
 */
public class DeathHandler {

    /**
     * Guards against firing multiple death pings for the same death event.
     * Set to {@code true} when a zero-health packet is processed and a ping is
     * dispatched; reset to {@code false} when a positive-health packet arrives.
     */
    private static boolean deathPingFired = false;

    /**
     * Called by {@link njkleb.cosmicteams.client.mixin.ClientPlayNetworkHandlerDeathMixin}
     * for every incoming health-update packet.
     *
     * <p>If {@code health <= 0} and no ping has been fired yet for this death
     * cycle, captures the player's current position and dispatches a death ping
     * via {@link RelayClient#sendDeathPing}.  If {@code health > 0} the guard
     * flag is reset so the next death will fire normally.</p>
     *
     * <p>Must be called on the game/main thread (guaranteed by the Minecraft
     * packet-handling pipeline).</p>
     *
     * @param health the health value from the incoming packet
     */
    public static void onHealthUpdate(float health) {
        if (health > 0f) {
            // Player is alive (or has respawned) — arm for the next death.
            deathPingFired = false;
            return;
        }

        // health == 0: player just died.
        if (deathPingFired) return; // already handled this death cycle
        deathPingFired = true;      // claim the slot before any async work

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (!RelayClient.isConnected() || !CosmicTeamsConfig.get().hasTeam()) return;

        double x     = mc.player.getX();
        double y     = mc.player.getY();
        double z     = mc.player.getZ();
        String world = mc.world.getRegistryKey().getValue().toString();

        RelayClient.sendDeathPing(x, y, z, world);
    }

    /**
     * Resets all transient state.  Call from the world-disconnect handler in
     * {@code CosmicTeamsClient} so that the next session starts clean.
     */
    public static void onDisconnect() {
        deathPingFired = false;
    }
}