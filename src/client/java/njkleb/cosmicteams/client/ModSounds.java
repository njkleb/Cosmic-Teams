package njkleb.cosmicteams.client;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class ModSounds {

    /**
     * Placeholder sound used until you drop a real OGG file at:
     *   src/client/resources/assets/cosmicteams/sounds/ping_alert.ogg
     *
     * Also make sure sounds.json exists at:
     *   src/client/resources/assets/cosmicteams/sounds.json
     * with this content:
     * {
     *   "ping_alert": {
     *     "sounds": [{ "name": "cosmicteams:ping_alert", "stream": false }]
     *   }
     * }
     *
     * Until the OGG file is present Minecraft will log a warning and play
     * nothing — the rest of the mod functions normally.
     */
    public static final SoundEvent PING_ALERT =
            SoundEvent.of(Identifier.of("cosmicteams", "ping_alert"));

    public static void register() {
        Registry.register(
                Registries.SOUND_EVENT,
                Identifier.of("cosmicteams", "ping_alert"),
                PING_ALERT);
    }
}