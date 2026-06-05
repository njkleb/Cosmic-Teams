package njkleb.cosmicteams.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Central handler for all CosmicTeams GUI screen overlays and tooltip
 * modifications.  Consolidates the former AdventureGuiHandler,
 * KothGuiHandler, and FacilityGuiHandler into one extensible file.
 *
 * <h2>Adding a new screen</h2>
 * <ol>
 *   <li>Create a {@code private static final class} that implements
 *       {@link ScreenDef} in the "Screen definitions" section below.</li>
 *   <li>Add an instance of it to {@link #SCREEN_DEFS}.</li>
 * </ol>
 * No other files need to change.
 *
 * <h2>Coordinate-system note</h2>
 * {@code HandledScreen.renderMain()} pushes a matrix translate of
 * {@code (this.x, this.y)} before calling {@code drawSlots()}, so by the
 * time {@link #renderSlotOverlay} fires the draw context is already in
 * GUI-local space.  {@code slot.x} / {@code slot.y} are therefore the
 * correct drawing coordinates directly — no {@code guiLeft/guiTop} offset.
 */
public class GuiOverlayHandler {

    // ═══════════════════════════════════════════════════════════════════════════
    //  Shared colour / style constants
    //  (package-private so ScreenDef inner classes can reference them directly)
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Overlay colours ───────────────────────────────────────────────────────
    static final int COLOR_WHITE   = 0xFFFFFFFF;
    static final int COLOR_LIME    = 0xFF55FF55;
    static final int COLOR_ORANGE  = 0xFFFFA500;
    static final int COLOR_MAGENTA = 0xFFFF55FF;

    // ── Slot background palette — reused across multiple screen defs ──────────
    /** Chain gear / Abandoned Ruins / generic fallback. */
    static final int BG_GRAY   = 0xFF606060;
    /** Iron gear / Lost Wasteland. */
    static final int BG_BLUE   = 0xFF90B8D0;
    /** Diamond gear / Demonic Realm. */
    static final int BG_YELLOW = 0xFFD4D060;
    /** Netherite gear / recent-ping alert. */
    static final int BG_RED    = 0xFFCC2222;

    // ── Text styles ───────────────────────────────────────────────────────────
    static final Style STYLE_LIME         = Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55));
    static final Style STYLE_ORANGE       = Style.EMPTY.withColor(TextColor.fromRgb(0xFFA500));
    static final Style STYLE_MAGENTA      = Style.EMPTY.withColor(TextColor.fromRgb(0xFF55FF));
    static final Style STYLE_BOLD_WHITE   = Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0xFFFFFF));
    static final Style STYLE_BOLD_LIME    = Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0x55FF55));
    static final Style STYLE_BOLD_ORANGE  = Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0xFFA500));
    static final Style STYLE_BOLD_MAGENTA = Style.EMPTY.withBold(true).withColor(TextColor.fromRgb(0xFF55FF));

    // ═══════════════════════════════════════════════════════════════════════════
    //  Periodic-refresh state  (shared — only one screen open at a time)
    // ═══════════════════════════════════════════════════════════════════════════

    private static volatile long lastTeamLocationFetch = 0L;
    private static final  long   REFRESH_MS            = 5_000L;

    // ═══════════════════════════════════════════════════════════════════════════
    //  ScreenDef — implement this interface to register a new GUI screen
    // ═══════════════════════════════════════════════════════════════════════════

    interface ScreenDef {

        /**
         * False if this screen's target slot should not have a solid background
         * drawn over it (e.g. Extractions, where the item icon should stay visible).
         * Default: draw the background.
         */
        default boolean shouldDrawBackground() { return true; }

        /**
         * Called once when the screen first opens (after the initial
         * {@link RelayClient#fetchTeamLocations()} call).  Override to trigger
         * additional fetches, e.g. {@link RelayClient#fetchExtractionEpochs()}.
         * Default: no-op.
         */
        default void onScreenOpened() {}

        /**
         * Called each time the periodic refresh fires while the screen is open.
         * Override to refresh screen-specific data alongside team locations.
         * Default: no-op.
         */
        default void onScreenRefresh() {}

        /** True when this definition owns the given open screen. */
        boolean matchesScreen(Screen screen);

        /**
         * True when the given item stack is a target slot for this screen
         * (i.e. one that should receive a background and overlay).
         */
        boolean matchesItem(ItemStack stack);

        /**
         * False to suppress teammate data and overlay rendering for this
         * particular stack (e.g. KOTH CLOSED).  The background is still drawn.
         * Default: always active.
         */
        default boolean isActive(ItemStack stack) { return true; }

        /**
         * Slot background colour for this stack.  Called unconditionally —
         * even when {@link #isActive} returns false.
         */
        int backgroundColor(ItemStack stack);

        /**
         * Sorted list of teammate names to display for this slot.
         * Only called when {@link #isActive} returns true.
         */
        List<String> getTeammates(ItemStack stack);

        /**
         * Renders per-screen extras (borders, numbers, etc.) on top of the
         * background.  Only called when {@link #isActive} returns true.
         * Use {@link GuiOverlayHandler#drawBorder} and
         * {@link GuiOverlayHandler#drawCenteredText} for common operations.
         *
         * @param ctx       draw context (already in GUI-local space)
         * @param baseX     {@code slot.x}
         * @param baseY     {@code slot.y}
         * @param stack     item in the slot
         * @param mc        Minecraft client instance
         * @param teammates pre-computed result of {@link #getTeammates}
         */
        void renderExtras(DrawContext ctx, int baseX, int baseY,
                          ItemStack stack, MinecraftClient mc, List<String> teammates);

        /**
         * Modifies the tooltip line list for a matched item.
         * Only called when {@link #isActive} returns true.
         * May clear and rebuild (adventure) or append only (koth, facility).
         *
         * @param stack     the hovered item
         * @param lines     mutable tooltip line list
         * @param teammates pre-computed result of {@link #getTeammates}
         */
        void buildTooltip(ItemStack stack, List<Text> lines, List<String> teammates);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Screen definitions
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Adventures ────────────────────────────────────────────────────────────
    //  Three screens (one per realm); items are chestplates.
    //  Lore is fully replaced.  Overlay: magenta adventure index (top-centre)
    //  + orange player count (bottom-centre) + lime border when teammates present.
    //  Background turns red when a recent teammate ping exists in this subworld.

    private static final class AdventureDef implements ScreenDef {

        private static final String[] TITLES = {
                "Abandoned Ruins Adventures",
                "Lost Wasteland Adventures",
                "Demonic Realm Adventures"
        };

        @Override
        public boolean matchesScreen(Screen screen) {
            if (!(screen instanceof HandledScreen)) return false;
            String title = screen.getTitle().getString();
            for (String t : TITLES) if (t.equals(title)) return true;
            return false;
        }

        @Override
        public boolean matchesItem(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return stack.isOf(Items.CHAINMAIL_CHESTPLATE)
                    || stack.isOf(Items.IRON_CHESTPLATE)
                    || stack.isOf(Items.DIAMOND_CHESTPLATE);
        }

        @Override
        public int backgroundColor(ItemStack stack) {
            // Ping alert takes priority over realm tint.
            if (RelayClient.hasRecentPingInSubworld(subworld(stack))) return BG_RED;
            // Tint by chestplate material → realm colour (chain=ruins, iron=wasteland, diamond=demonic).
            if (stack.isOf(Items.CHAINMAIL_CHESTPLATE)) return BG_GRAY;
            if (stack.isOf(Items.IRON_CHESTPLATE))      return BG_BLUE;
            if (stack.isOf(Items.DIAMOND_CHESTPLATE))   return BG_YELLOW;
            return BG_GRAY;
        }

        @Override
        public List<String> getTeammates(ItemStack stack) {
            return teammatesBySubworld(subworld(stack));
        }

        @Override
        public void renderExtras(DrawContext ctx, int baseX, int baseY,
                                 ItemStack stack, MinecraftClient mc, List<String> teammates) {
            String sw = subworld(stack);
            if (sw.isEmpty()) return;

            if (!teammates.isEmpty()) drawBorder(ctx, baseX, baseY, COLOR_LIME);

            // Magenta adventure index — top-centre (y+0)
            int num = adventureNumber(sw);
            if (num >= 0) {
                drawCenteredText(ctx, mc, Text.literal(String.valueOf(num)).setStyle(STYLE_BOLD_MAGENTA),
                        baseX, baseY, COLOR_MAGENTA);
            }

            // Orange global player count — bottom-centre (y+9; 6-px font ends at y+15)
            int players = playerCount(stack);
            if (players >= 0) {
                drawCenteredText(ctx, mc, Text.literal(String.valueOf(players)).setStyle(STYLE_BOLD_ORANGE),
                        baseX, baseY + 9, COLOR_ORANGE);
            }
        }

        @Override
        public void buildTooltip(ItemStack stack, List<Text> lines, List<String> teammates) {
            String sw        = subworld(stack);
            int    players   = playerCount(stack);
            int    maxPlayers = maxCount(stack);

            lines.clear();
            lines.add(Text.literal(formatSubworldName(sw)).setStyle(STYLE_BOLD_WHITE));
            String countLabel = maxPlayers > 0
                    ? players + " / " + maxPlayers + " Adventurers"
                    : players + " Adventurers";
            lines.add(Text.literal(countLabel).setStyle(STYLE_ORANGE));
            for (String name : teammates) lines.add(Text.literal(name).setStyle(STYLE_LIME));
        }

        // ── Adventure-specific helpers ────────────────────────────────────────

        private String subworld(ItemStack stack) {
            for (String line : rawLoreLines(stack)) {
                String clean = line.replaceAll("[<>]", "").trim();
                if (clean.startsWith("Click to travel to "))
                    return clean.substring("Click to travel to ".length()).trim();
            }
            return "";
        }

        private int playerCount(ItemStack stack) {
            for (String line : rawLoreLines(stack)) {
                int slash = line.indexOf(" / ");
                if (slash < 0) continue;
                try { return Integer.parseInt(line.substring(0, slash).trim()); }
                catch (NumberFormatException ignored) {}
            }
            return -1;
        }

        private int maxCount(ItemStack stack) {
            for (String line : rawLoreLines(stack)) {
                int slash = line.indexOf(" / ");
                if (slash < 0) continue;
                try { return Integer.parseInt(line.substring(slash + 3).trim()); }
                catch (NumberFormatException ignored) {}
            }
            return 0;
        }

        private int adventureNumber(String subworld) {
            int i = subworld.length();
            while (i > 0 && Character.isDigit(subworld.charAt(i - 1))) i--;
            if (i == subworld.length()) return -1;
            try { return Integer.parseInt(subworld.substring(i)); }
            catch (NumberFormatException e) { return -1; }
        }

        private String formatSubworldName(String subworld) {
            if (subworld.startsWith("adventure"))
                return "Adventure " + subworld.substring("adventure".length());
            if (subworld.isEmpty()) return subworld;
            return Character.toUpperCase(subworld.charAt(0)) + subworld.substring(1);
        }
    }

    // ── KOTH ──────────────────────────────────────────────────────────────────
    //  Single screen; item is a chestplate indicating the gear limit.
    //  Processing is gated on OPEN status (skipped when CLOSED).
    //  Lore is appended (not replaced).
    //  Overlay: bold lime teammate count centred in the slot.

    private static final class KothDef implements ScreenDef {

        private static final String TITLE    = "KOTH";
        private static final String SUBWORLD = "koth";

        @Override
        public boolean matchesScreen(Screen screen) {
            return screen instanceof HandledScreen
                    && TITLE.equals(screen.getTitle().getString());
        }

        @Override
        public boolean matchesItem(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return stack.isOf(Items.CHAINMAIL_CHESTPLATE)
                    || stack.isOf(Items.IRON_CHESTPLATE)
                    || stack.isOf(Items.DIAMOND_CHESTPLATE)
                    || stack.isOf(Items.NETHERITE_CHESTPLATE);
        }

        /** Suppresses teammate data when the "Status:" lore line reads CLOSED. */
        @Override
        public boolean isActive(ItemStack stack) {
            for (String line : rawLoreLines(stack)) {
                if (line.startsWith("Status: "))
                    return !"CLOSED".equalsIgnoreCase(line.substring("Status: ".length()).trim());
            }
            return true; // no status line found → assume open
        }

        /** Background colour encodes the gear limit via chestplate material. */
        @Override
        public int backgroundColor(ItemStack stack) {
            if (stack.isOf(Items.CHAINMAIL_CHESTPLATE))  return BG_GRAY;
            if (stack.isOf(Items.IRON_CHESTPLATE))       return BG_BLUE;
            if (stack.isOf(Items.DIAMOND_CHESTPLATE))    return BG_YELLOW;
            if (stack.isOf(Items.NETHERITE_CHESTPLATE))  return BG_RED;
            return BG_GRAY;
        }

        @Override
        public List<String> getTeammates(ItemStack stack) {
            return teammatesBySubworld(SUBWORLD);
        }

        @Override
        public void renderExtras(DrawContext ctx, int baseX, int baseY,
                                 ItemStack stack, MinecraftClient mc, List<String> teammates) {
            if (teammates.isEmpty()) return;
            drawBorder(ctx, baseX, baseY, COLOR_LIME);
            // Bold lime count — vertically centred: (16 - 6) / 2 = y+5
            drawCenteredText(ctx, mc, Text.literal(String.valueOf(teammates.size()))
                    .setStyle(STYLE_BOLD_LIME), baseX, baseY + 5, COLOR_LIME);
        }

        @Override
        public void buildTooltip(ItemStack stack, List<Text> lines, List<String> teammates) {
            for (String name : teammates) lines.add(Text.literal(name).setStyle(STYLE_LIME));
        }
    }

    // ── Facility ──────────────────────────────────────────────────────────────
    //  Single screen; item is a chest.  Active facility identified from lore.
    //  Background colour encodes the active realm.
    //  Lore is appended (not replaced).
    //  Overlay: bold lime teammate count centred in the slot.
    //  Matching uses teammateWorlds (world key) rather than teammateSubworlds.

    private static final class FacilityDef implements ScreenDef {

        private static final String TITLE = "Facility Loot";

        // World-key substrings — matched with String.contains()
        private static final String WORLD_RUINS     = "adventure_ruins_facility-0";
        private static final String WORLD_WASTELAND = "adventure_wasteland_facility-0";
        private static final String WORLD_DEMONIC   = "adventure_demonic_realm_facility-0";

        @Override
        public boolean matchesScreen(Screen screen) {
            return screen instanceof HandledScreen
                    && TITLE.equals(screen.getTitle().getString());
        }

        @Override
        public boolean matchesItem(ItemStack stack) {
            return !stack.isEmpty() && stack.isOf(Items.CHEST);
        }

        @Override
        public int backgroundColor(ItemStack stack) {
            return switch (activeFacility(stack)) {
                case "ruins"     -> BG_GRAY;
                case "wasteland" -> BG_BLUE;
                case "demonic"   -> BG_YELLOW;
                default          -> BG_GRAY;
            };
        }

        @Override
        public List<String> getTeammates(ItemStack stack) {
            String worldKey = switch (activeFacility(stack)) {
                case "ruins"     -> WORLD_RUINS;
                case "wasteland" -> WORLD_WASTELAND;
                case "demonic"   -> WORLD_DEMONIC;
                default          -> "";
            };
            return teammatesByWorld(worldKey);
        }

        @Override
        public void renderExtras(DrawContext ctx, int baseX, int baseY,
                                 ItemStack stack, MinecraftClient mc, List<String> teammates) {
            // ── Alignment border — always drawn when alignment is known ───────────
            // Shows the facility's current alignment at a glance.  The lime
            // teammate border overwrites this when teammates are present, so lime
            // always takes priority without needing any additional Z logic.
            int alignColor = alignmentBorderColor(stack);
            if (alignColor != 0) drawBorder(ctx, baseX, baseY, alignColor);

            // ── Teammate lime border + centred count ──────────────────────────────
            if (teammates.isEmpty()) return;
            drawBorder(ctx, baseX, baseY, COLOR_LIME);
            drawCenteredText(ctx, mc, Text.literal(String.valueOf(teammates.size()))
                    .setStyle(STYLE_BOLD_LIME), baseX, baseY + 5, COLOR_LIME);
        }

        @Override
        public void buildTooltip(ItemStack stack, List<Text> lines, List<String> teammates) {
            for (String name : teammates) lines.add(Text.literal(name).setStyle(STYLE_LIME));
        }

        // ── Facility-specific helpers ─────────────────────────────────────────

        /**
         * Returns a short tag for the active facility by reading the "Active:"
         * lore line.  Handles both inline and next-line formats.
         *
         * @return "ruins", "wasteland", "demonic", or "" if unrecognised
         */
        private String activeFacility(ItemStack stack) {
            List<String> lines = rawLoreLines(stack);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.startsWith("Active:")) continue;
                String inline = line.substring("Active:".length()).trim();
                String value  = inline.isEmpty() && i + 1 < lines.size()
                        ? lines.get(i + 1).trim() : inline;
                if (value.contains("Abandoned Ruins")) return "ruins";
                if (value.contains("Lost Wasteland"))  return "wasteland";
                if (value.contains("Demonic Realm"))   return "demonic";
            }
            return "";
        }

        /**
         * or 0 if the alignment line is absent or unrecognised.
         *
         * <ul>
         *   <li>Chaotic → red    ({@link GuiOverlayHandler#BG_RED})</li>
         *   <li>Neutral → orange ({@link GuiOverlayHandler#COLOR_ORANGE})</li>
         *   <li>Lawful  → white  ({@link GuiOverlayHandler#COLOR_WHITE})</li>
         * </ul>
         *
         * Parses the "Current Alignment:" lore line, handling both the inline
         * form ("Current Alignment: Chaotic") and a label-then-value layout.
         */
        private int alignmentBorderColor(ItemStack stack) {
            List<String> lines = rawLoreLines(stack);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.startsWith("Current Alignment:")) continue;
                String inline = line.substring("Current Alignment:".length()).trim();
                String value  = inline.isEmpty() && i + 1 < lines.size()
                        ? lines.get(i + 1).trim() : inline;
                if (value.contains("Chaotic")) return BG_RED;
                if (value.contains("Neutral")) return COLOR_ORANGE;
                if (value.contains("Lawful"))  return COLOR_WHITE;
            }
            return 0; // unrecognised — draw no border
        }
    }

    // ── Extractions ───────────────────────────────────────────────────────────
    //  Single screen; item is a barrel or dead bush.  No background is drawn so
    //  the item icon stays visible.  Overlay: bold lime teammate count centred
    //  in slot when teammates are present.  Tooltip shows the item name and
    //  loadout line, then estimated extraction timers (magenta), then teammates
    //  with their world label, e.g. "Player (Extraction 2)".
    //
    //  Teammate matching uses world key (contains "extraction_map-").
    //  Estimated timers are derived from epoch timestamps stored server-side and
    //  fetched into RelayClient.extractionEpochs on screen open / refresh.

    private static final class ExtractionsDef implements ScreenDef {

        private static final String TITLE = "Extractions";

        @Override public boolean matchesScreen(Screen screen) {
            return screen instanceof HandledScreen
                    && TITLE.equals(screen.getTitle().getString());
        }

        @Override public boolean matchesItem(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return stack.isOf(Items.BARREL) || stack.isOf(Items.DEAD_BUSH);
        }

        /** No background — item icon should remain visible. */
        @Override public boolean shouldDrawBackground() { return false; }
        @Override public int     backgroundColor(ItemStack stack) { return 0; }

        /** Fetch extraction epochs in addition to team locations on screen open. */
        @Override public void onScreenOpened()  { RelayClient.fetchExtractionEpochs(); }
        @Override public void onScreenRefresh() { RelayClient.fetchExtractionEpochs(); }

        @Override
        public List<String> getTeammates(ItemStack stack) {
            return teammatesByWorld("extraction_map-");
        }

        @Override
        public void renderExtras(DrawContext ctx, int baseX, int baseY,
                                 ItemStack stack, MinecraftClient mc, List<String> teammates) {
            if (teammates.isEmpty()) return;
            // Bold lime count centred — drawn over the item icon with shadow for legibility.
            drawCenteredText(ctx, mc, Text.literal(String.valueOf(teammates.size()))
                    .setStyle(STYLE_BOLD_LIME), baseX, baseY + 5, COLOR_LIME);
        }

        @Override
        public void buildTooltip(ItemStack stack, List<Text> lines, List<String> teammates) {
            // ── Preserve item name and loadout line ───────────────────────────
            Text itemName   = lines.isEmpty() ? Text.literal(TITLE) : lines.getFirst();
            Text loadoutLine = null;
            for (Text t : lines) {
                if (t.getString().contains("Current Loadout:")) { loadoutLine = t; break; }
            }
            lines.clear();
            lines.add(itemName);
            if (loadoutLine != null) lines.add(loadoutLine);

            // ── Estimated extraction timers ───────────────────────────────────
            // Each entry in extractionEpochs is the moment a world's 20:00
            // timer started.  Convert to current countdown values and display.
            for (String timerStr : epochsToTimerStrings()) {
                lines.add(Text.literal(timerStr).setStyle(STYLE_MAGENTA));
            }

            // ── Teammates with world label ────────────────────────────────────
            String self = selfName();
            for (String name : teammates) {
                if (name.equals(self)) continue;
                String world = RelayClient.teammateWorlds.getOrDefault(name, "");
                lines.add(Text.literal(name + " (" + extractionLabel(world) + ")")
                        .setStyle(STYLE_LIME));
            }
        }

        // ── Extraction-specific helpers ───────────────────────────────────────

        /**
         * Converts a world registry key containing "extraction_map-N" into the
         * display label "Extraction N" shown next to each teammate in the tooltip.
         */
        private String extractionLabel(String world) {
            int idx = world.indexOf("extraction_map-");
            if (idx < 0) return "Extraction";
            String after = world.substring(idx + "extraction_map-".length());
            StringBuilder digits = new StringBuilder();
            for (char c : after.toCharArray()) {
                if (Character.isDigit(c)) digits.append(c); else break;
            }
            String num = digits.toString();
            return "Extraction " + (!num.isEmpty() ? num : "?");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Registry  ← add new ScreenDef instances here to enable them
    // ═══════════════════════════════════════════════════════════════════════════

    private static final List<ScreenDef> SCREEN_DEFS = List.of(
            new AdventureDef(),
            new KothDef(),
            new FacilityDef(),
            new ExtractionsDef()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    //  Public API  (called from CosmicTeamsClient and HandledScreenSlotOverlayMixin)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Registers all screen-lifecycle and tooltip event hooks.
     * Call once from {@link CosmicTeamsClient#onInitializeClient()}.
     */
    public static void register() {

        // ── Screen open: initial fetch + periodic refresh ─────────────────────
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            ScreenDef def = defForScreen(screen);
            if (def == null) return;

            if (RelayClient.isConnected() && CosmicTeamsConfig.get().hasTeam()) {
                lastTeamLocationFetch = System.currentTimeMillis();
                RelayClient.fetchTeamLocations();
                def.onScreenOpened();
            }

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (!RelayClient.isConnected() || !CosmicTeamsConfig.get().hasTeam()) return;
                long now = System.currentTimeMillis();
                if (now - lastTeamLocationFetch >= REFRESH_MS) {
                    lastTeamLocationFetch = now;
                    RelayClient.fetchTeamLocations();
                    def.onScreenRefresh();
                }
            });
        });

        // ── Tooltip modification ──────────────────────────────────────────────
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, type, lines) -> {
            MinecraftClient mc  = MinecraftClient.getInstance();
            ScreenDef       def = defForScreen(mc.currentScreen);
            if (def == null || !def.matchesItem(stack) || !def.isActive(stack)) return;
            def.buildTooltip(stack, lines, def.getTeammates(stack));
        });
    }

    /**
     * Draws the slot background and extras for the first matching
     * {@link ScreenDef}.  Called from {@code HandledScreenSlotOverlayMixin}.
     */
    public static void renderSlotOverlay(DrawContext ctx, Slot slot) {
        MinecraftClient mc  = MinecraftClient.getInstance();
        ScreenDef       def = defForScreen(mc.currentScreen);
        if (def == null) return;

        ItemStack stack = slot.getStack();
        if (!def.matchesItem(stack)) return;

        int baseX = slot.x;
        int baseY = slot.y;

        // Background is drawn only when the screen def requests it (Extractions skips it
        // so the barrel/dead-bush icon stays visible underneath the count overlay).
        if (def.shouldDrawBackground()) {
            ctx.fill(baseX, baseY, baseX + 16, baseY + 16, def.backgroundColor(stack));
        }

        if (!def.isActive(stack)) return;

        List<String> teammates = def.getTeammates(stack);
        def.renderExtras(ctx, baseX, baseY, stack, mc, teammates);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Shared utilities  (package-private — available to all ScreenDef classes)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Draws a one-pixel border immediately outside the 16×16 slot area
     * in the given ARGB colour.
     */
    static void drawBorder(DrawContext ctx, int baseX, int baseY, int color) {
        int x1 = baseX - 1, y1 = baseY - 1, x2 = baseX + 17, y2 = baseY + 17;
        ctx.fill(x1, y1,          x2, baseY,        color); // top
        ctx.fill(x1, baseY + 16,  x2, y2,            color); // bottom
        ctx.fill(x1, baseY,       baseX, baseY + 16,  color); // left
        ctx.fill(baseX + 16, baseY, x2,  baseY + 16,  color); // right
    }

    /** Draws {@code text} horizontally centred on {@code centreX + 8}. */
    static void drawCenteredText(DrawContext ctx, MinecraftClient mc,
                                 Text text, int baseX, int y, int colour) {
        int w = mc.textRenderer.getWidth(text);
        ctx.drawText(mc.textRenderer, text, baseX + 8 - w / 2, y, colour, true);
    }

    /**
     * Returns lore lines as plain text with Minecraft formatting codes stripped.
     */
    static List<String> rawLoreLines(ItemStack stack) {
        List<String> result = new ArrayList<>();
        var lore = stack.get(DataComponentTypes.LORE);
        if (lore != null) {
            for (Text t : lore.lines())
                result.add(t.getString().replaceAll("§[0-9a-fA-Fk-oK-OrRlLmMnN]", ""));
        }
        return result;
    }

    /**
     * Teammates whose last reported subworld equals {@code subworld}
     * (case-insensitive), excluding the local player, sorted alphabetically.
     */
    static List<String> teammatesBySubworld(String subworld) {
        if (subworld.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        String self = selfName();
        for (var e : RelayClient.teammateSubworlds.entrySet()) {
            if (!e.getKey().equals(self) && subworld.equalsIgnoreCase(e.getValue()))
                result.add(e.getKey());
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    /**
     * Teammates whose last reported world key contains {@code worldKeySubstring},
     * excluding the local player, sorted alphabetically.
     */
    static List<String> teammatesByWorld(String worldKeySubstring) {
        if (worldKeySubstring.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        String self = selfName();
        for (var e : RelayClient.teammateWorlds.entrySet()) {
            if (!e.getKey().equals(self) && e.getValue().contains(worldKeySubstring))
                result.add(e.getKey());
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the first ScreenDef matching the given screen, or null. */
    private static ScreenDef defForScreen(Screen screen) {
        if (screen == null) return null;
        for (ScreenDef def : SCREEN_DEFS) {
            if (def.matchesScreen(screen)) return def;
        }
        return null;
    }

    private static String selfName() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null ? mc.player.getName().getString() : "";
    }

    // ── Extraction timer utilities ────────────────────────────────────────────

    /**
     * Converts a list of extraction world-start epochs into display strings
     * (format {@code "mm:ss"}) for the Extractions GUI tooltip.
     *
     * <p>For each epoch, the displayed value is computed as follows:
     * <ul>
     *   <li><b>Epoch &lt; 5 min old</b> — world is still open to new players.<br>
     *       Display = {@code 20:00 − elapsed} (natural countdown value in [15:00, 20:00]).
     *   </li>
     *   <li><b>Epoch 5–20 min old</b> — world is locked; estimate the timer of the
     *       most recently spawned successor world using modular arithmetic.<br>
     *       Display = {@code 20:00 − (elapsed mod 5:00)}, giving values in (15:00, 20:00).
     *       Example: elapsed = 13:30 → 13:30 mod 5:00 = 3:30 → display 16:30.
     *   </li>
     *   <li><b>Epoch ≥ 20 min old</b> — expired; skipped entirely.</li>
     * </ul>
     *
     * <p>Results are sorted with the lowest remaining time first (most urgent),
     * and near-duplicate values (within 15 s of each other) are deduplicated.
     *
     * @return formatted timer strings ready to add as tooltip lines
     */
    static List<String> epochsToTimerStrings() {
        long now         = System.currentTimeMillis();
        long expireMs    = 20L * 60 * 1_000;
        long fiveMinMs   = 5L  * 60 * 1_000;
        int  fiveMinSecs = 5   * 60;

        List<Integer> timerSeconds = new ArrayList<>();
        for (long epoch : RelayClient.extractionEpochs) {
            long elapsed = now - epoch;
            if (elapsed < 0 || elapsed >= expireMs) continue; // skip future or expired
            int elapsedSecs = (int) (elapsed / 1_000);
            int timerSecs;
            if (elapsed < fiveMinMs) {
                // World still open — show natural countdown.
                timerSecs = 20 * 60 - elapsedSecs;
            } else {
                // World locked — estimate successor world's timer via modulo.
                int cycle = elapsedSecs % fiveMinSecs;
                timerSecs = 20 * 60 - cycle;
            }
            timerSeconds.add(timerSecs);
        }

        // Sort ascending (least time remaining first = most urgent).
        timerSeconds.sort(Integer::compareTo);

        // Deduplicate: drop values within 15 s of the previous kept value.
        List<String> result = new ArrayList<>();
        int lastKept = Integer.MIN_VALUE;
        for (int secs : timerSeconds) {
            if (secs - lastKept >= 15) {
                result.add(String.format("%d:%02d", secs / 60, secs % 60));
                lastKept = secs;
            }
        }
        return result;
    }

    /**
     * Reads the "Time Left:" line from the sidebar scoreboard and returns the
     * remaining extraction time in seconds.
     *
     * <p>Returns {@code -1} if the scoreboard objective is absent or the
     * "Time Left:" line cannot be parsed (e.g. on world entry before the
     * scoreboard populates).  Returns {@code 0} for sub-minute timers
     * (the "55s" format), which the caller treats as "world locked".</p>
     *
     * <p>Package-private so {@link CosmicTeamsClient} can call it from the
     * tick handler to compute and submit the world-start epoch.</p>
     */
    static int readExtractionSecondsFromScoreboard() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return -1;
        var scoreboard = mc.world.getScoreboard();
        var objective  = scoreboard.getObjectiveForSlot(
                net.minecraft.scoreboard.ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return -1;

        for (var score : scoreboard.getScoreboardEntries(objective)) {
            String line = buildScoreboardLine(score, scoreboard)
                    .replaceAll("(?i)§[0-9A-FK-OR]", "");
            if (!line.contains("Time Left:")) continue;

            String seg  = line.substring(line.indexOf("Time Left:") + "Time Left:".length()).trim();
            int    mIdx = seg.indexOf('m');
            int    sIdx = seg.indexOf('s');
            try {
                if (mIdx >= 0) {
                    int minutes = Integer.parseInt(seg.substring(0, mIdx).trim());
                    int seconds = 0;
                    if (sIdx > mIdx + 1)
                        seconds = Integer.parseInt(seg.substring(mIdx + 1, sIdx).trim());
                    return minutes * 60 + seconds;
                } else if (sIdx >= 0) {
                    // "55s" format — timer is below 1 minute, world is locked.
                    return Integer.parseInt(seg.substring(0, sIdx).trim());
                }
            } catch (NumberFormatException ignored) {}
        }
        return -1; // line not found; caller will retry next tick
    }

    /** Assembles the display text for a single scoreboard entry. */
    private static String buildScoreboardLine(
            net.minecraft.scoreboard.ScoreboardEntry score,
            net.minecraft.scoreboard.Scoreboard scoreboard) {
        if (score.display() != null) return score.display().getString();
        String owner = score.owner();
        var    team  = scoreboard.getScoreHolderTeam(owner);
        if (team != null)
            return team.getPrefix().getString() + owner + team.getSuffix().getString();
        return owner;
    }
}