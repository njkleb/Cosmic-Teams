package njkleb.cosmicteams.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Persists per-account data (auth token, team name, role) and global client
 * settings (visuals, audio, UX) in a single JSON file.
 *
 * <h2>Two distinct data domains</h2>
 * <ul>
 *   <li>{@code accounts} — per-Minecraft-UUID team membership data.</li>
 *   <li>{@link Settings settings} — machine-local client preferences.</li>
 * </ul>
 *
 * <h2>Role model</h2>
 * <ul>
 *   <li>{@code "owner"}     — full control</li>
 *   <li>{@code "moderator"} — invite, kick, promote/demote members</li>
 *   <li>{@code "member"}    — ping only</li>
 * </ul>
 */
public class CosmicTeamsConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH =
            FabricLoader.getInstance().getConfigDir().resolve("cosmicteams.json");

    // ── Per-account data ──────────────────────────────────────────────────────

    public static class AccountData {
        String  token    = null;
        String  teamName = null;
        String  role     = null;
        boolean isOwner  = false;
    }

    private Map<String, AccountData> accounts = new HashMap<>();

    // ── Global client settings ────────────────────────────────────────────────

    /**
     * All client-side preferences, shared across accounts on the same machine.
     *
     * <h2>How to add a new setting</h2>
     * <ol>
     *   <li>Declare a {@code public} field here with a sensible default and Javadoc.</li>
     *   <li>Add a null/range guard to {@link #validate()}.</li>
     *   <li>Add a row to the appropriate {@code build*Tab()} in
     *       {@link CosmicTeamsConfigScreen}.</li>
     *   <li>Add the field to the matching {@code case} in
     *       {@code CosmicTeamsConfigScreen.resetCurrentTab()} so the per-tab
     *       reset button covers it.</li>
     *   <li>Read it anywhere via {@code CosmicTeamsConfig.get().settings.yourField}.</li>
     * </ol>
     */
    public static class Settings {

        // ── Beacon visuals ────────────────────────────────────────────────────

        /** How long (seconds) beacons remain visible. Range: 10–300. Default: 60. */
        public int beaconLifetimeSecs = 60;

        /**
         * RGB color for teammates' beacon beams and HUD labels.
         * Default: 0xFFFFFF (white).
         */
        public int teamBeaconColor = 0xFFFFFF;

        /** Whether this player's own beacons use a separate color. Default: false. */
        public boolean useCustomOwnColor = false;

        /**
         * RGB color for this player's own beacon beam (only when
         * {@link #useCustomOwnColor} is true). Default: 0x55FF55 (lime).
         */
        public int ownBeaconColor = 0x55FF55;

        /** Inner (bright core) beam opacity, 0–255. Default: 100. */
        public int innerBeamAlpha = 100;

        /** Outer (soft glow) beam opacity, 0–255. Default: 45. */
        public int outerBeamAlpha = 45;

        // ── Death ping visuals ────────────────────────────────────────────────

        /**
         * How long (seconds) death beacon beams remain visible.
         * Range: 30–600. Default: 300 (5 minutes).
         */
        public int deathBeaconLifetimeSecs = 300;

        /**
         * RGB color for death beacon beams and their HUD labels.
         * Default: 0xFF5555 (red).
         */
        public int deathBeaconColor = 0xFF5555;

        /**
         * RGB color for the player name in death ping HUD labels.
         * Default: 0xFF5555 (red).
         */
        public int deathLabelNameColor = 0xFF5555;

        // ── Audio ─────────────────────────────────────────────────────────────

        /** Ping-alert volume, 0.0–1.0. Default: 0.40. */
        public float pingVolume = 0.40f;

        /**
         * Dimension-key suffix prefixes for which ping alert sounds are muted.
         * Matching is prefix-based: {@code "skyblock_locker_"} mutes all
         * {@code skyblock_locker_N} dimensions. Default: empty (nothing muted).
         */
        public Set<String> mutedWorldKeys = new HashSet<>();

        /**
         * When {@code true}, the death-ping alert sound plays regardless of
         * whether the receiver is in the same world as the deceased.
         * When {@code false} (default), the sound is suppressed unless the
         * receiver is in the matching world and subworld.
         */
        public boolean deathPingAudioGlobal = false;

        // ── HUD labels ────────────────────────────────────────────────────────

        /** Whether to render the sender's name above beacons. Default: true. */
        public boolean showNameLabel = true;

        /** Whether to render the distance (e.g. "50m") in labels. Default: true. */
        public boolean showDistLabel = true;

        /** Whether to render the beacon age (e.g. "16s") in labels. Default: true. */
        public boolean showAgeLabel = true;

        /** RGB color for the player name in ping HUD labels. Default: 0xFFAA00 (gold). */
        public int labelNameColor = 0xFFAA00;

        /** RGB color for the distance text in ping HUD labels. Default: 0x55FFFF (aqua). */
        public int labelDistColor = 0x55FFFF;

        /** RGB color for the age text in ping HUD labels. Default: 0xFF5555 (red). */
        public int labelAgeColor  = 0xFF5555;

        // ── Misc / UX ─────────────────────────────────────────────────────────

        /**
         * Whether to print relay reconnection/disconnection messages in chat.
         * Auth confirmations and errors are always shown. Default: true.
         */
        public boolean showConnectionMessages = true;

        /**
         * When true, ping chat lines are shortened:
         * received → "Player pinged.", sent → "Pinged at X, Y, Z". Default: false.
         */
        public boolean compactPingMessages = false;

        /**
         * Maximum raycast range (blocks) for aim-ping mode (long-press B).
         * Range: 100–1000. Default: 500.
         */
        public int aimPingRange = 500;

        // ── Helpers ───────────────────────────────────────────────────────────

        public long beaconLifetimeMs()      { return beaconLifetimeSecs * 1000L; }
        public long deathBeaconLifetimeMs() { return deathBeaconLifetimeSecs * 1000L; }

        public boolean isMuted(String worldKey) {
            String suffix = worldKey.contains(":") ? worldKey.split(":", 2)[1] : worldKey;
            for (String muteKey : mutedWorldKeys) {
                if (suffix.equals(muteKey) || suffix.startsWith(muteKey)) return true;
            }
            return false;
        }

        public boolean isMutedKey(String keySuffix) { return mutedWorldKeys.contains(keySuffix); }

        public void setMuted(String keySuffix, boolean muted) {
            if (muted) mutedWorldKeys.add(keySuffix);
            else       mutedWorldKeys.remove(keySuffix);
        }

        public int effectiveBeaconColor(String senderName) {
            MinecraftClient mc = MinecraftClient.getInstance();
            String myName = (mc.player != null) ? mc.player.getName().getString() : "";
            return (useCustomOwnColor && senderName.equals(myName))
                    ? ownBeaconColor : teamBeaconColor;
        }

        /** Resets every setting to its factory default. */
        public void resetToDefaults() {
            beaconLifetimeSecs      = 60;
            teamBeaconColor         = 0xFFFFFF; // white
            useCustomOwnColor       = false;
            ownBeaconColor          = 0x55FF55;
            innerBeamAlpha          = 100;
            outerBeamAlpha          = 45;
            deathBeaconLifetimeSecs = 300;
            deathBeaconColor        = 0xFF5555;
            deathLabelNameColor     = 0xFF5555;
            pingVolume              = 0.40f;
            mutedWorldKeys          = new HashSet<>();
            deathPingAudioGlobal    = false;
            showNameLabel           = true;
            showDistLabel           = true;
            showAgeLabel            = true;
            labelNameColor          = 0xFFAA00;
            labelDistColor          = 0x55FFFF;
            labelAgeColor           = 0xFF5555;
            showConnectionMessages  = true;
            compactPingMessages     = false;
            aimPingRange            = 500;
        }

        void validate() {
            if (mutedWorldKeys == null) mutedWorldKeys = new HashSet<>();
            beaconLifetimeSecs      = Math.max(10,  Math.min(300,  beaconLifetimeSecs));
            deathBeaconLifetimeSecs = Math.max(30,  Math.min(600,  deathBeaconLifetimeSecs));
            innerBeamAlpha          = Math.max(0,   Math.min(255,  innerBeamAlpha));
            outerBeamAlpha          = Math.max(0,   Math.min(255,  outerBeamAlpha));
            pingVolume              = Math.max(0f,  Math.min(1f,   pingVolume));
            aimPingRange            = Math.max(100, Math.min(1000, aimPingRange));
        }
    }

    public Settings settings = new Settings();

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static CosmicTeamsConfig instance    = null;
    private static String            currentUuid = null;

    public static void initForCurrentPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.getSession() != null) {
            UUID uuid = client.getSession().getUuidOrNull();
            currentUuid = uuid != null ? uuid.toString().replace("-", "") : null;
        }
        if (instance == null) instance = load();
    }

    public static CosmicTeamsConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private static CosmicTeamsConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                CosmicTeamsConfig config =
                        GSON.fromJson(Files.readString(CONFIG_PATH), CosmicTeamsConfig.class);
                if (config != null) {
                    if (config.accounts == null) config.accounts = new HashMap<>();
                    if (config.settings == null) config.settings = new Settings();
                    config.settings.validate();
                    return config;
                }
            } catch (IOException e) {
                System.err.println("[CosmicTeams] Failed to load config: " + e.getMessage());
            }
        }
        return new CosmicTeamsConfig();
    }

    public void save() {
        try {
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[CosmicTeams] Failed to save config: " + e.getMessage());
        }
    }

    // ── Account accessors ─────────────────────────────────────────────────────

    private AccountData getCurrentAccount() {
        if (currentUuid == null) return new AccountData();
        return accounts.computeIfAbsent(currentUuid, k -> new AccountData());
    }

    public String getToken()    { return getCurrentAccount().token; }
    public String getTeamName() { return getCurrentAccount().teamName; }

    public String getRole() {
        AccountData a = getCurrentAccount();
        if (a.role != null && !a.role.isEmpty()) return a.role;
        return a.isOwner ? "owner" : "member";
    }

    public boolean isOwner()     { return "owner".equals(getRole()); }
    public boolean isModerator() { return "moderator".equals(getRole()) || isOwner(); }

    public boolean hasTeam() {
        AccountData a = getCurrentAccount();
        return a.token != null && a.teamName != null;
    }

    public void setTeamData(String token, String teamName) {
        AccountData a = getCurrentAccount();
        a.token    = token;
        a.teamName = teamName;
        save();
    }

    public void setRole(String role) {
        AccountData a = getCurrentAccount();
        a.role = (role != null && !role.isEmpty()) ? role : "member";
        save();
    }

    public void clearTeamData() {
        AccountData a = getCurrentAccount();
        a.token    = null;
        a.teamName = null;
        a.role     = "member";
        a.isOwner  = false;
        save();
    }
}