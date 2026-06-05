package njkleb.cosmicteams.client;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps dimension key suffixes (the part after the ':') to short display names
 * shown in ping chat messages.
 *
 * <h2>Dimension-only nicknames ({@link #SHORTHANDS})</h2>
 * <p>Two types of matching are supported:
 * <ul>
 *   <li><b>Prefix</b> — if the dimension key <em>starts with</em> the key string,
 *       the shorthand is used.  Useful for dynamic dimension families like
 *       {@code skyblock_locker_0}, {@code skyblock_locker_1}, etc.</li>
 *   <li><b>Exact</b> — if the dimension key equals the key string exactly.</li>
 * </ul>
 *
 * <p>Entries are evaluated in insertion order, so put more specific keys before
 * broader prefixes if there is any risk of overlap.
 *
 * <p>Each value is returned <em>as-is</em> — include your own Minecraft color/format
 * codes directly in the value string.  For example:
 * <pre>{@code
 *   SHORTHANDS.put("skyblock_locker_", "§6Locker");          // gold
 *   SHORTHANDS.put("koth",             "§c§lKotH");          // bold red
 * }</pre>
 *
 * <h2>World + subworld nicknames ({@link #SUBWORLD_SHORTHANDS})</h2>
 * <p>These are checked first when {@link #displayName(String, String)} is called.
 * Both the dim-suffix key and the subworld key support prefix or exact matching.
 * When a match is found its value is returned directly (include your own color codes).
 *
 * <pre>{@code
 *   // "overworld" dimension + any subworld starting with "spawn" → "§aSpawn"
 *   SUBWORLD_SHORTHANDS.put(new SubworldKey("overworld", "spawn"), "§aSpawn");
 *
 *   // Exact dim + exact subworld
 *   SUBWORLD_SHORTHANDS.put(new SubworldKey("overworld", "jungle3"), "§2Jungle");
 * }</pre>
 *
 * <p>Unknown dimension names are shown as-is, truncated to
 * {@value #MAX_DISPLAY_LEN} characters.
 */
public class WorldNames {

    // ── Composite key for world + subworld lookups ────────────────────────────

    /**
     * Composite lookup key used in {@link #SUBWORLD_SHORTHANDS}.
     *
     * <p>Both fields support prefix matching (checked via
     * {@link String#startsWith}) or exact matching.
     *
     * @param dimSuffix the dimension key suffix (after {@code :}), e.g. {@code "overworld"}
     * @param subworld  the subworld name, e.g. {@code "spawn9"} or a common prefix
     *                  like {@code "spawn"} to catch {@code spawn0}–{@code spawn9}
     */
    public record SubworldKey(String dimSuffix, String subworld) {}

    // ── World + subworld shorthand table ─────────────────────────────────────
    // Checked before SHORTHANDS when displayName(dimSuffix, subworld) is called.
    // Values are returned as-is; include your own color codes.

    private static final LinkedHashMap<SubworldKey, String> SUBWORLD_SHORTHANDS = new LinkedHashMap<>();

    static {
        SUBWORLD_SHORTHANDS.put(new SubworldKey("overworld", "spawn"),  "§7Spawn");
        SUBWORLD_SHORTHANDS.put(new SubworldKey("world_end", "lobby"),  "§7Hub");
    }

    // ── Dimension-only shorthand table ────────────────────────────────────────
    // Values are returned as-is — include Minecraft color/format codes in the value.
    // Keys are matched as either an exact string or a prefix of the dimension suffix.

    private static final LinkedHashMap<String, String> SHORTHANDS = new LinkedHashMap<>();

    static {
        // ── Add dimension-only entries here ───────────────────────────────────
        SHORTHANDS.put("skyblock_locker_",   "§3Locker");
        SHORTHANDS.put("adventure_wasteland-0","§bIron Adventure");
        SHORTHANDS.put("adventure_ruins-0",    "§dChain Adventure");
        SHORTHANDS.put("adventure_demonic_realm-0",    "§eDiamond Adventure");
        SHORTHANDS.put("adventure_wasteland_facility-0","§bIron Facility");
        SHORTHANDS.put("adventure_ruins_facility-0",    "§dChain Facility");
        SHORTHANDS.put("adventure_demonic_realm_facility-0",    "§eDiamond Facility");
        SHORTHANDS.put("world_lms",          "§9LMS");
        SHORTHANDS.put("skyblock_world_",    "§aSkyblock");
        SHORTHANDS.put("koth",            "§6KOTH");
        // ─────────────────────────────────────────────────────────────────────
    }

    /** Maximum display length for unrecognised dimension names. */
    private static final int MAX_DISPLAY_LEN = 30;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a chat-ready display string for the given dimension key suffix,
     * checking {@link #SUBWORLD_SHORTHANDS} first (using both the dimension and
     * subworld), then falling back to {@link #SHORTHANDS} (dimension only).
     *
     * <p>If no shorthand matches, the raw dimension name is returned, truncated
     * to {@value #MAX_DISPLAY_LEN} characters.
     *
     * <p>Values from both maps are returned <em>as-is</em> and may contain
     * Minecraft color codes.
     *
     * @param dimSuffix the dimension key suffix, e.g. {@code "skyblock_locker_3"}
     * @param subworld  the current subworld name, e.g. {@code "spawn9"};
     *                  pass an empty string if unknown or not applicable
     * @return a display string, never {@code null}
     */
    public static String displayName(String dimSuffix, String subworld) {
        // Check world+subworld table first when a subworld is present
        if (subworld != null && !subworld.isEmpty()) {
            for (Map.Entry<SubworldKey, String> entry : SUBWORLD_SHORTHANDS.entrySet()) {
                SubworldKey key = entry.getKey();
                boolean dimMatch = dimSuffix.equals(key.dimSuffix())
                        || dimSuffix.startsWith(key.dimSuffix());
                boolean swMatch  = subworld.equals(key.subworld())
                        || subworld.startsWith(key.subworld());
                if (dimMatch && swMatch) return entry.getValue();
            }
        }
        // Fall through to dimension-only table
        return displayName(dimSuffix);
    }

    /**
     * Returns a chat-ready display string for the given dimension key suffix
     * (i.e. the part after the {@code :}, such as {@code skyblock_locker_3}
     * or {@code overworld}), consulting only {@link #SHORTHANDS}.
     *
     * <p>If a shorthand matches (prefix or exact), its value is returned as-is
     * (the value may include Minecraft color codes).  Otherwise the raw name is
     * returned, truncated to {@value #MAX_DISPLAY_LEN} characters.
     *
     * @param dimSuffix the dimension key suffix, e.g. {@code "skyblock_locker_3"}
     * @return a display string, never {@code null}
     */
    public static String displayName(String dimSuffix) {
        for (Map.Entry<String, String> entry : SHORTHANDS.entrySet()) {
            String key = entry.getKey();
            if (dimSuffix.equals(key) || dimSuffix.startsWith(key)) {
                return entry.getValue();
            }
        }
        if (dimSuffix.length() > MAX_DISPLAY_LEN) {
            return dimSuffix.substring(0, MAX_DISPLAY_LEN) + "…";
        }
        return dimSuffix;
    }
}