package njkleb.cosmicteams.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Drawable;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * The CosmicTeams client settings screen.
 * Open via the "Open Config" keybinding (assign in Controls), or:
 *   client.setScreen(new CosmicTeamsConfigScreen(client.currentScreen));
 *
 * <h2>How to add a new setting</h2>
 * <ol>
 *   <li>Declare the field in {@link CosmicTeamsConfig.Settings}.</li>
 *   <li>Add one line in the appropriate {@code build*Tab()} method:
 *     <pre>
 *       addSetting("Label", makeToggle(() -> s.myBool,  v -> s.myBool  = v));
 *       addSetting("Label", makeSlider(0, 100, s.myInt, "%", v -> s.myInt = v));
 *       addSetting("Label", makeColorPicker(() -> s.myColor, v -> s.myColor = v));
 *     </pre>
 *   </li>
 *   <li>Add the field to the matching {@code case} in {@link #resetCurrentTab()}
 *       so the per-tab reset button covers it.</li>
 * </ol>
 *
 * <h2>Color format reminder</h2>
 * Every int color passed to {@code drawTextWithShadow} /
 * {@code drawCenteredTextWithShadow} must include an explicit {@code 0xFF} alpha
 * byte (e.g. {@code 0xFFCCCCCC}, not {@code 0xCCCCCC}). See {@link #COL_LABEL} etc.
 */
public class CosmicTeamsConfigScreen extends Screen {

    // ── ARGB text-draw color constants ────────────────────────────────────────

    private static final int COL_TITLE  = 0xFFFFFFFF;
    private static final int COL_LABEL  = 0xFFCCCCCC;
    private static final int COL_HEADER = 0xFFFFAA00;
    private static final int COL_RULE   = 0x88FFAA00;

    // ── Tabs ──────────────────────────────────────────────────────────────────

    public enum Tab {
        BEACONS("Beacons"),
        AUDIO  ("Audio"),
        DISPLAY("Display"),
        MISC   ("Misc");

        final String label;
        Tab(String label) { this.label = label; }
    }

    // ── Color preset table ────────────────────────────────────────────────────

    private record ColorEntry(String name, int rgb) {}

    private static final ColorEntry[] COLORS = {
            new ColorEntry("White",     0xFFFFFF),
            new ColorEntry("Aqua",      0x55FFFF),
            new ColorEntry("Gold",      0xFFAA00),
            new ColorEntry("Red",       0xFF5555),
            new ColorEntry("Lime",      0x55FF55),
            new ColorEntry("Green",     0x00AA00),
            new ColorEntry("Pink",      0xFF55FF),
            new ColorEntry("Purple",    0xAA00AA),
            new ColorEntry("Orange",    0xFF6600),
            new ColorEntry("Sky Blue",  0x00AAFF),
            new ColorEntry("Yellow",    0xFFFF55),
            new ColorEntry("Blue",      0x5555FF),
            new ColorEntry("Dark Red",  0xAA0000),
            new ColorEntry("Teal",      0x00AAAA),
    };

    // ── Layout constants ──────────────────────────────────────────────────────

    private static final int CONTENT_TOP       = 50;
    private static final int ROW_H             = 24;
    private static final int HEADER_H          = 22;
    private static final int SPACER_H          = 10;
    private static final int WIDGET_W          = 150;
    private static final int WIDGET_H          = 20;
    private static final int WIDGET_COL_OFFSET = 5;

    // ── Row model ─────────────────────────────────────────────────────────────

    private enum RowKind { SETTING, HEADER, SPACER }

    private static class Row {
        final RowKind        kind;
        final String         label;
        final ClickableWidget widget;
        int baseY;

        Row(RowKind kind, String label, ClickableWidget widget) {
            this.kind = kind; this.label = label; this.widget = widget;
        }
    }

    // ── Screen state ──────────────────────────────────────────────────────────

    private final Screen parent;
    private final Tab activeTab;

    private int scrollOffset    = 0;
    private int maxScrollOffset = 0;

    private final Map<Tab, ButtonWidget> tabButtons = new EnumMap<>(Tab.class);
    private final List<Row>              rows       = new ArrayList<>();
    private final List<Row>              widgetRows = new ArrayList<>();

    // ── Constructors ──────────────────────────────────────────────────────────

    public CosmicTeamsConfigScreen(Screen parent) {
        this(parent, Tab.BEACONS);
    }

    public CosmicTeamsConfigScreen(Screen parent, Tab initialTab) {
        super(Text.literal("CosmicTeams Settings"));
        this.parent    = parent;
        this.activeTab = initialTab;
    }

    // ── Screen lifecycle ──────────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        rows.clear();
        widgetRows.clear();
        tabButtons.clear();

        // ── Tab bar ───────────────────────────────────────────────────────────
        int tabW      = 77;
        int tabGap    = 2;
        int totalTabW = Tab.values().length * tabW + (Tab.values().length - 1) * tabGap;
        int tabStartX = (this.width - totalTabW) / 2;

        for (Tab tab : Tab.values()) {
            int tx = tabStartX + tab.ordinal() * (tabW + tabGap);
            ButtonWidget btn = ButtonWidget.builder(tabText(tab), b -> switchTab(tab))
                    .dimensions(tx, 22, tabW, 18)
                    .build();
            tabButtons.put(tab, btn);
            addDrawableChild(btn);
        }

        // ── Bottom buttons: Done (left) + Reset Tab (right) ───────────────────
        // Both sit inside the 320 px panel, separated by an 8 px gap.
        int btnY = this.height - 26;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"), b -> close()
        ).dimensions(this.width / 2 - 154, btnY, 150, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Reset Tab")
                        .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555))),
                b -> resetCurrentTab()
        ).dimensions(this.width / 2 + 4, btnY, 150, 20).build());

        // ── Content rows ──────────────────────────────────────────────────────
        buildCurrentTab();

        int y       = CONTENT_TOP;
        int widgetX = this.width / 2 + WIDGET_COL_OFFSET;

        for (Row row : rows) {
            row.baseY = y;
            if (row.widget != null) {
                row.widget.setPosition(widgetX, y);
                widgetRows.add(row);
                addDrawableChild(row.widget);
            }
            y += rowHeight(row);
        }

        int totalH  = y - CONTENT_TOP;
        int viewH   = this.height - CONTENT_TOP - 36;
        maxScrollOffset = Math.max(0, totalH - viewH);
        scrollOffset    = Math.min(scrollOffset, maxScrollOffset);

        applyScroll();
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {

        // 1. super.render() first — nothing before this
        super.render(ctx, mouseX, mouseY, delta);

        int panelX = this.width / 2 - 160;
        int panelW = 320;
        int panelY = CONTENT_TOP - 4;
        int panelB = this.height - 32;

        // 2. Panel fills
        ctx.fill(panelX,              panelY,     panelX + panelW, panelB,     0xBB000000);
        ctx.fill(panelX,              panelY,     panelX + panelW, panelY + 1, 0x66FFFFFF);
        ctx.fill(panelX,              panelB - 1, panelX + panelW, panelB,     0x66FFFFFF);
        ctx.fill(panelX,              panelY,     panelX + 1,      panelB,     0x44FFFFFF);
        ctx.fill(panelX + panelW - 1, panelY,     panelX + panelW, panelB,     0x44FFFFFF);

        // 3. Text (title, headers, labels)
        ctx.drawCenteredTextWithShadow(
                this.textRenderer, this.title, this.width / 2, 8, COL_TITLE);

        for (Row row : rows) {
            int y = row.baseY - scrollOffset;
            int h = rowHeight(row);
            if (y + h <= CONTENT_TOP || y >= panelB) continue;

            switch (row.kind) {
                case HEADER -> {
                    Text headerText = Text.literal(" " + row.label + " ")
                            .setStyle(Style.EMPTY
                                    .withBold(true)
                                    .withColor(TextColor.fromRgb(0xFFAA00)));
                    int tw    = this.textRenderer.getWidth(headerText);
                    int textX = this.width / 2 - tw / 2;
                    int lineY = y + h / 2 - 1;
                    ctx.fill(panelX + 8,     lineY, textX - 4,            lineY + 1, COL_RULE);
                    ctx.fill(textX + tw + 4, lineY, panelX + panelW - 8, lineY + 1, COL_RULE);
                    ctx.drawTextWithShadow(this.textRenderer, headerText,
                            textX, y + (h - 9) / 2, COL_HEADER);
                }
                case SETTING -> {
                    if (!row.label.isEmpty()) {
                        int textW     = this.textRenderer.getWidth(row.label);
                        int rightEdge = this.width / 2 - 8;
                        int textY     = y + (WIDGET_H - 9) / 2 + 2;
                        ctx.drawTextWithShadow(this.textRenderer, row.label,
                                rightEdge - textW, textY, COL_LABEL);
                    }
                }
                default -> { /* SPACER */ }
            }
        }

        // 4. Re-render children on top of the panel fill
        for (var child : this.children()) {
            if (child instanceof Drawable d) {
                d.render(ctx, mouseX, mouseY, delta);
            }
        }

        // 5. Final overlays
        ButtonWidget activeBtn = tabButtons.get(activeTab);
        if (activeBtn != null) {
            int bx = activeBtn.getX();
            int by = activeBtn.getY() + activeBtn.getHeight();
            ctx.fill(bx, by, bx + activeBtn.getWidth(), by + 2, 0xFFFFAA00);
        }

        if (maxScrollOffset > 0) {
            int sbX   = panelX + panelW - 5;
            int sbH   = panelB - CONTENT_TOP;
            int knobH = Math.max(16, sbH * sbH / (sbH + maxScrollOffset));
            int knobY = CONTENT_TOP
                    + (int)((float) scrollOffset / maxScrollOffset * (sbH - knobH));
            ctx.fill(sbX, CONTENT_TOP, sbX + 3, panelB,        0x33FFFFFF);
            ctx.fill(sbX, knobY,       sbX + 3, knobY + knobH, 0xBBFFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double horizontalAmount, double verticalAmount) {
        if (maxScrollOffset > 0) {
            scrollOffset = Math.max(0, Math.min(maxScrollOffset,
                    scrollOffset - (int)(verticalAmount * 14)));
            applyScroll();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void close() {
        assert this.client != null;
        this.client.setScreen(parent);
    }

    // ── Per-tab reset ─────────────────────────────────────────────────────────

    /**
     * Resets only the settings belonging to the currently visible tab, then
     * reopens the screen so widgets reflect the new values.
     * When you add a new setting, add its reset line to the matching case here.
     * Keep defaults in sync with the field declarations in
     * {@link CosmicTeamsConfig.Settings}.
     */
    private void resetCurrentTab() {
        CosmicTeamsConfig.Settings s = CosmicTeamsConfig.get().settings;

        switch (activeTab) {
            case BEACONS -> {
                s.beaconLifetimeSecs      = 60;
                s.teamBeaconColor         = 0xFFFFFF; // white
                s.useCustomOwnColor       = false;
                s.ownBeaconColor          = 0x55FF55;
                s.innerBeamAlpha          = 100;
                s.outerBeamAlpha          = 45;
                s.deathBeaconLifetimeSecs = 300;
                s.deathBeaconColor        = 0xFF5555;
                s.deathLabelNameColor     = 0xFF5555;
            }
            case AUDIO -> {
                s.pingVolume           = 0.40f;
                s.mutedWorldKeys       = new HashSet<>();
                s.deathPingAudioGlobal = false;
            }
            case DISPLAY -> {
                s.showNameLabel  = true;
                s.showDistLabel  = true;
                s.showAgeLabel   = true;
                s.labelNameColor = 0xFFAA00;
                s.labelDistColor = 0x55FFFF;
                s.labelAgeColor  = 0xFF5555;
            }
            case MISC -> {
                s.showConnectionMessages = true;
                s.compactPingMessages    = false;
                s.aimPingRange           = 500;
            }
        }

        CosmicTeamsConfig.get().save();
        MinecraftClient.getInstance()
                .setScreen(new CosmicTeamsConfigScreen(parent, activeTab));
    }

    // ── Tab content builders ──────────────────────────────────────────────────

    private void buildCurrentTab() {
        switch (activeTab) {
            case BEACONS -> buildBeaconsTab();
            case AUDIO   -> buildAudioTab();
            case DISPLAY -> buildDisplayTab();
            case MISC    -> buildMiscTab();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BEACONS TAB
    // ─────────────────────────────────────────────────────────────────────────
    private void buildBeaconsTab() {
        CosmicTeamsConfig.Settings s = CosmicTeamsConfig.get().settings;

        addHeader("Lifetime & Opacity");
        addSetting("Beacon Lifetime",
                makeSlider(10, 300, s.beaconLifetimeSecs, "s",
                        v -> s.beaconLifetimeSecs = v));
        addSetting("Inner Beam Opacity",
                makeSlider(0, 100, alphaToPercent(s.innerBeamAlpha), "%",
                        v -> s.innerBeamAlpha = percentToAlpha(v)));
        addSetting("Outer Beam Opacity",
                makeSlider(0, 100, alphaToPercent(s.outerBeamAlpha), "%",
                        v -> s.outerBeamAlpha = percentToAlpha(v)));

        addHeader("Beam Colors");
        addSetting("Teammate Color",
                makeColorPicker(() -> s.teamBeaconColor, v -> s.teamBeaconColor = v));
        addSetting("Custom Own Color",
                makeToggle(() -> s.useCustomOwnColor, v -> s.useCustomOwnColor = v));
        addSetting("Own Beacon Color",
                makeColorPicker(() -> s.ownBeaconColor, v -> s.ownBeaconColor = v));

        addHeader("Death Pings");
        addSetting("Death Beacon Lifetime",
                makeSlider(30, 600, s.deathBeaconLifetimeSecs, "s",
                        v -> s.deathBeaconLifetimeSecs = v));
        addSetting("Death Beacon Color",
                makeColorPicker(() -> s.deathBeaconColor, v -> s.deathBeaconColor = v));
        addSetting("Death Label Name Color",
                makeColorPicker(() -> s.deathLabelNameColor, v -> s.deathLabelNameColor = v));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  AUDIO TAB
    // ─────────────────────────────────────────────────────────────────────────
    private void buildAudioTab() {
        CosmicTeamsConfig.Settings s = CosmicTeamsConfig.get().settings;

        addHeader("Volume");
        addSetting("Ping Alert Volume",
                makeSlider(0, 100, Math.round(s.pingVolume * 100), "%",
                        v -> s.pingVolume = v / 100f));

        addHeader("Muted Worlds");
        addMuteToggle("Locker",     s, "skyblock_locker_");
        addMuteToggle("Skyblock",   s, "skyblock_world_");
        addMuteToggle("Adventures", s, "adventure_");
        addMuteToggle("LMS",        s, "world_lms");
        addMuteToggle("Overworld",  s, "overworld");
        addMuteToggle("Nether",     s, "the_nether");
        addMuteToggle("The End",    s, "the_end");

        addHeader("Death Ping Audio");
        addSetting("Global Death Alert",
                makeToggle(() -> s.deathPingAudioGlobal, v -> s.deathPingAudioGlobal = v));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  DISPLAY TAB
    // ─────────────────────────────────────────────────────────────────────────
    private void buildDisplayTab() {
        CosmicTeamsConfig.Settings s = CosmicTeamsConfig.get().settings;

        addHeader("Ping HUD Labels");
        addSetting("Show Player Name",
                makeToggle(() -> s.showNameLabel, v -> s.showNameLabel = v));
        addSetting("Show Distance",
                makeToggle(() -> s.showDistLabel, v -> s.showDistLabel = v));
        addSetting("Show Age Counter",
                makeToggle(() -> s.showAgeLabel,  v -> s.showAgeLabel  = v));

        addHeader("Label Colors");
        addSetting("Name Color",
                makeColorPicker(() -> s.labelNameColor, v -> s.labelNameColor = v));
        addSetting("Distance Color",
                makeColorPicker(() -> s.labelDistColor, v -> s.labelDistColor = v));
        addSetting("Age Color",
                makeColorPicker(() -> s.labelAgeColor,  v -> s.labelAgeColor  = v));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MISC TAB
    // ─────────────────────────────────────────────────────────────────────────
    private void buildMiscTab() {
        CosmicTeamsConfig.Settings s = CosmicTeamsConfig.get().settings;

        addHeader("Chat Messages");
        addSetting("Connection Alerts",
                makeToggle(() -> s.showConnectionMessages, v -> s.showConnectionMessages = v));
        addSetting("Compact Ping Text",
                makeToggle(() -> s.compactPingMessages, v -> s.compactPingMessages = v));

        addHeader("Ping Behavior");
        addSetting("Aim-Ping Range",
                makeSlider(100, 1000, s.aimPingRange, " blks",
                        v -> s.aimPingRange = v));
    }

    // ── Row builder helpers ───────────────────────────────────────────────────

    private void addSetting(String label, ClickableWidget widget) {
        rows.add(new Row(RowKind.SETTING, label, widget));
    }

    private void addHeader(String text) {
        rows.add(new Row(RowKind.HEADER, text, null));
    }

    private void addMuteToggle(String label, CosmicTeamsConfig.Settings s, String keySuffix) {
        addSetting(label, makeMuteToggle(
                () -> s.isMutedKey(keySuffix),
                muted -> s.setMuted(keySuffix, muted)
        ));
    }

    // ── Widget factories ──────────────────────────────────────────────────────

    private ClickableWidget makeSlider(int min, int max, int current,
                                       String suffix, IntConsumer onChange) {
        return new IntSlider(0, 0, WIDGET_W, WIDGET_H, min, max, current, suffix, v -> {
            onChange.accept(v);
            CosmicTeamsConfig.get().save();
        });
    }

    private ClickableWidget makeToggle(BooleanSupplier getter, Consumer<Boolean> setter) {
        return ButtonWidget.builder(toggleText(getter.getAsBoolean()), btn -> {
            boolean next = !getter.getAsBoolean();
            setter.accept(next);
            btn.setMessage(toggleText(next));
            CosmicTeamsConfig.get().save();
        }).dimensions(0, 0, WIDGET_W, WIDGET_H).build();
    }

    /**
     * A specialised toggle for mute entries showing "Unmuted" (green, default)
     * or "Muted" (red) rather than the generic "Enabled"/"Disabled" labels used
     * by {@link #makeToggle}.  The inverted colour convention (red = active/on)
     * reinforces that muting is an exceptional state.
     */
    private ClickableWidget makeMuteToggle(BooleanSupplier getter, Consumer<Boolean> setter) {
        return ButtonWidget.builder(muteToggleText(getter.getAsBoolean()), btn -> {
            boolean next = !getter.getAsBoolean();
            setter.accept(next);
            btn.setMessage(muteToggleText(next));
            CosmicTeamsConfig.get().save();
        }).dimensions(0, 0, WIDGET_W, WIDGET_H).build();
    }

    private ClickableWidget makeColorPicker(IntSupplier getter, IntConsumer setter) {
        int[] idx = { colorIndex(getter.getAsInt()) };
        return ButtonWidget.builder(colorText(idx[0]), btn -> {
            idx[0] = (idx[0] + 1) % COLORS.length;
            setter.accept(COLORS[idx[0]].rgb());
            btn.setMessage(colorText(idx[0]));
            CosmicTeamsConfig.get().save();
        }).dimensions(0, 0, WIDGET_W, WIDGET_H).build();
    }

    // ── Scroll ────────────────────────────────────────────────────────────────

    private void applyScroll() {
        int viewTop = CONTENT_TOP;
        int viewBot = this.height - 36;
        for (Row row : widgetRows) {
            int actualY = row.baseY - scrollOffset;
            row.widget.setPosition(row.widget.getX(), actualY);
            boolean vis = actualY >= viewTop && (actualY + WIDGET_H) <= viewBot;
            row.widget.visible = vis;
            row.widget.active  = vis;
        }
    }

    // ── Tab switching ─────────────────────────────────────────────────────────

    private void switchTab(Tab tab) {
        MinecraftClient.getInstance().setScreen(new CosmicTeamsConfigScreen(parent, tab));
    }

    // ── Text helpers ──────────────────────────────────────────────────────────

    private Text tabText(Tab tab) {
        return tab == activeTab
                ? Text.literal(tab.label).setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55)).withUnderline(true))
                : Text.literal(tab.label);
    }

    private static Text toggleText(boolean on) {
        return on
                ? Text.literal("Enabled").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55)))
                : Text.literal("Disabled").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)));
    }

    /** "Unmuted" (green) when not muted; "Muted" (red) when muted. */
    private static Text muteToggleText(boolean muted) {
        return muted
                ? Text.literal("Muted").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555)))
                : Text.literal("Unmuted").setStyle(
                Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55)));
    }

    private static Text colorText(int idx) {
        return Text.literal("■ " + COLORS[idx].name())
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(COLORS[idx].rgb())));
    }

    // ── Layout helpers ────────────────────────────────────────────────────────

    private static int rowHeight(Row row) {
        return switch (row.kind) {
            case HEADER  -> HEADER_H;
            case SPACER  -> SPACER_H;
            case SETTING -> ROW_H;
        };
    }

    // ── Color helpers ─────────────────────────────────────────────────────────

    private static int colorIndex(int rgb) {
        for (int i = 0; i < COLORS.length; i++) {
            if (COLORS[i].rgb() == rgb) return i;
        }
        int    best     = 0;
        double bestDist = Double.MAX_VALUE;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        for (int i = 0; i < COLORS.length; i++) {
            int cr = (COLORS[i].rgb() >> 16) & 0xFF;
            int cg = (COLORS[i].rgb() >>  8) & 0xFF;
            int cb =  COLORS[i].rgb()         & 0xFF;
            double d = (double)(r-cr)*(r-cr) + (double)(g-cg)*(g-cg) + (double)(b-cb)*(b-cb);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    private static int alphaToPercent(int alpha) {
        return Math.round(alpha / 255f * 100f);
    }

    private static int percentToAlpha(int percent) {
        return Math.round(percent / 100f * 255f);
    }

    // ── IntSlider ─────────────────────────────────────────────────────────────

    private static class IntSlider extends SliderWidget {

        private final int         min;
        private final int         max;
        private final String      suffix;
        private final IntConsumer onChange;

        IntSlider(int x, int y, int w, int h,
                  int min, int max, int current,
                  String suffix, IntConsumer onChange) {
            super(x, y, w, h, Text.empty(),
                    (max > min) ? (double)(current - min) / (max - min) : 0.0);
            this.min      = min;
            this.max      = max;
            this.suffix   = suffix;
            this.onChange = onChange;
            updateMessage();
        }

        int getIntValue() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal(getIntValue() + suffix));
        }

        @Override
        protected void applyValue() {
            onChange.accept(getIntValue());
        }
    }
}