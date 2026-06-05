package njkleb.cosmicteams.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Read-only inventory view screen opened by {@code /invsee <username>}.
 *
 * <p>Displays a teammate's full inventory snapshot received from the relay server.
 * The layout mirrors the vanilla player inventory (minus the crafting grid):
 * <pre>
 *   [H][C][L][B]  ·  ·  ·  ·  [O]   ← helmet / chest / leggings / boots / offhand
 *   [ 9][10][11][12][13][14][15][16][17]
 *   [18][19][20][21][22][23][24][25][26]
 *   [27][28][29][30][31][32][33][34][35]
 *   ─────────────────────────────────── (visual hotbar separator)
 *   [ 0][ 1][ 2][ 3][ 4][ 5][ 6][ 7][ 8]
 * </pre>
 *
 * <p>Slot index mapping (matches {@link RelayClient#serializeInventory}):
 * <ul>
 *   <li>0–35: {@code main[0..35]} (0–8 = hotbar, 9–35 = main grid)</li>
 *   <li>36: boots, 37: leggings, 38: chestplate, 39: helmet</li>
 *   <li>40: offhand</li>
 * </ul>
 *
 * <p>The screen is non-pausing and closes on Escape or the inventory key.</p>
 */
public class InvSeeScreen extends Screen {

    // ── Background dimensions ─────────────────────────────────────────────────

    private static final int BG_W = 176;
    private static final int BG_H = 130;

    // ── Slot layout constants (relative to background top-left) ──────────────

    /** Y of the equipment row (armor + offhand). */
    private static final int EQUIP_Y   = 20;
    /** Y of the first main-inventory row (slots 9–17). */
    private static final int MAIN_Y    = 44;
    /** Y of the hotbar row (slots 0–8). MAIN_Y + 3*18 + 4 gap */
    private static final int HOTBAR_Y  = 102;
    /** X of the leftmost slot column. */
    private static final int SLOTS_X   = 8;

    // ── Colors ────────────────────────────────────────────────────────────────

    private static final int COL_BG_OUTER  = 0xFF8B8B8B;
    private static final int COL_BG_INNER  = 0xFFC6C6C6;
    private static final int COL_SLOT_DARK = 0xFF373737;
    private static final int COL_SLOT_LITE = 0xFFFFFFFF;
    private static final int COL_SLOT_FILL = 0xFF8B8B8B;
    private static final int COL_SEPARATOR = 0xFF9F9F9F;
    private static final int COL_TITLE     = 0xFF404040;
    private static final int COL_HOVER     = 0x80FFFFFF;

    // ── State ─────────────────────────────────────────────────────────────────

    /** Username whose inventory is being displayed. */
    private final String playerName;
    /**
     * 41 item stacks in the order produced by {@link RelayClient#serializeInventory}.
     * Never null; empty slots are {@link ItemStack#EMPTY}.
     */
    private final List<ItemStack> stacks;

    // ── Constructor ───────────────────────────────────────────────────────────

    public InvSeeScreen(String playerName, List<ItemStack> stacks) {
        super(Text.literal(playerName + "'s Inventory"));
        this.playerName = playerName;
        this.stacks     = stacks;
    }

    // ── Screen overrides ──────────────────────────────────────────────────────

    /**
     * Prevents the game from pausing in single-player while this screen is open.
     * Screen.shouldPause() defaults to {@code true}, so we must override it.
     */
    @Override
    public boolean shouldPause() { return false; }

    /**
     * Closes the screen when the inventory key is pressed, mirroring how vanilla
     * containers (chests, barrels, etc.) respond to that binding.
     *
     * <p>{@code Screen.keyPressed} takes a {@link KeyInput} record
     * (wrapping key, scancode, and modifiers) in this version, confirmed by the
     * decompiled {@code Screen.java}. {@code KeyBinding.matchesKey} takes the same
     * type, replacing the old two-arg {@code (int keyCode, int scanCode)} form.</p>
     */
    @Override
    public boolean keyPressed(KeyInput input) {
        if (super.keyPressed(input)) return true;
        if (client != null && client.options.inventoryKey.matchesKey(input)) {
            this.close();
            return true;
        }
        return false;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {

        int bgX = (this.width  - BG_W) / 2;
        int bgY = (this.height - BG_H) / 2;

        drawBackground(context, bgX, bgY);
        drawTitle(context, bgX, bgY);
        drawSeparators(context, bgX, bgY);

        // Render all 41 slots; track which one the mouse is hovering over.
        int hoveredSlot = -1;
        for (int i = 0; i < 41; i++) {
            int[] pos = slotPos(i);
            if (pos == null) continue;
            int sx = bgX + pos[0];
            int sy = bgY + pos[1];
            drawSlotBackground(context, sx, sy);
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) {
                context.drawItem(stack, sx + 1, sy + 1);
                context.drawStackOverlay(textRenderer, stack, sx + 1, sy + 1);
            }
            if (isMouseOver(mouseX, mouseY, sx, sy)) {
                hoveredSlot = i;
            }
        }

        // Draw hover highlight and tooltip last so they render on top.
        if (hoveredSlot >= 0) {
            int[] pos = slotPos(hoveredSlot);
            if (pos != null) {
                context.fill(bgX + pos[0] + 1, bgY + pos[1] + 1,
                        bgX + pos[0] + 17, bgY + pos[1] + 17, COL_HOVER);
            }
            ItemStack hovered = stacks.get(hoveredSlot);
            if (!hovered.isEmpty()) {
                context.drawItemTooltip(textRenderer, hovered, mouseX, mouseY);
            }
        }

        // Render any child widgets registered in init() (none currently, but
        // calling super keeps the contract correct for future additions).
        super.render(context, mouseX, mouseY, delta);
    }

    // ── Drawing helpers ───────────────────────────────────────────────────────

    private void drawBackground(DrawContext ctx, int bgX, int bgY) {
        // Outer shadow border.
        ctx.fill(bgX, bgY, bgX + BG_W, bgY + BG_H, COL_BG_OUTER);
        // Light inner panel.
        ctx.fill(bgX + 1, bgY + 1, bgX + BG_W - 1, bgY + BG_H - 1, COL_BG_INNER);
    }

    private void drawTitle(DrawContext ctx, int bgX, int bgY) {
        String title = playerName + "'s Inventory";
        int tx = bgX + (BG_W - textRenderer.getWidth(title)) / 2;
        ctx.drawText(textRenderer, title, tx, bgY + 7, COL_TITLE, false);
    }

    private void drawSeparators(DrawContext ctx, int bgX, int bgY) {
        // Below title.
        ctx.fill(bgX + 2, bgY + 17, bgX + BG_W - 2, bgY + 18, COL_SEPARATOR);
        // Between main inventory and hotbar.
        ctx.fill(bgX + SLOTS_X, bgY + HOTBAR_Y - 3,
                bgX + SLOTS_X + 9 * 18, bgY + HOTBAR_Y - 2, COL_SEPARATOR);
    }

    /**
     * Draws an 18×18 inset slot cell at the given screen position.
     * The item icon sits at {@code (x+1, y+1)} and is 16×16 pixels.
     */
    private void drawSlotBackground(DrawContext ctx, int x, int y) {
        ctx.fill(x,     y,     x + 18, y + 1,  COL_SLOT_DARK); // top
        ctx.fill(x,     y,     x + 1,  y + 18, COL_SLOT_DARK); // left
        ctx.fill(x,     y + 17, x + 18, y + 18, COL_SLOT_LITE); // bottom
        ctx.fill(x + 17, y,    x + 18, y + 18, COL_SLOT_LITE); // right
        ctx.fill(x + 1,  y + 1, x + 17, y + 17, COL_SLOT_FILL); // interior
    }

    /**
     * Returns true if the mouse is over any part of the 18×18 slot cell at (sx, sy).
     *
     * <p>The check covers the full cell (borders included) rather than just the
     * 16×16 icon interior. Adjacent slots share an edge with no pixel gap between
     * them, so this prevents the tooltip from flickering as the mouse crosses
     * from one slot to the next.
     */
    private static boolean isMouseOver(int mx, int my, int sx, int sy) {
        return mx >= sx && mx < sx + 18 && my >= sy && my < sy + 18;
    }

    // ── Slot position mapping ─────────────────────────────────────────────────

    /**
     * Returns {@code {relX, relY}} of the slot with the given index, relative to
     * the background origin. Returns {@code null} for unmapped indices.
     *
     * <p>Slot index → display position:
     * <ul>
     *   <li>0–8:  hotbar (one row at the bottom)</li>
     *   <li>9–35: main inventory (3×9 grid above hotbar)</li>
     *   <li>36:   boots   → equipment row, column 3</li>
     *   <li>37:   leggings → equipment row, column 2</li>
     *   <li>38:   chestplate → equipment row, column 1</li>
     *   <li>39:   helmet  → equipment row, column 0</li>
     *   <li>40:   offhand → equipment row, column 8 (far right)</li>
     * </ul>
     */
    private static int[] slotPos(int index) {
        if (index >= 0 && index <= 8) {
            // Hotbar
            return new int[]{ SLOTS_X + index * 18, HOTBAR_Y };
        }
        if (index >= 9 && index <= 35) {
            // Main inventory (3 rows of 9)
            int sub = index - 9;
            return new int[]{ SLOTS_X + (sub % 9) * 18, MAIN_Y + (sub / 9) * 18 };
        }
        return switch (index) {
            case 36 -> new int[]{ SLOTS_X + 3 * 18, EQUIP_Y }; // boots
            case 37 -> new int[]{ SLOTS_X + 2 * 18, EQUIP_Y }; // leggings
            case 38 -> new int[]{ SLOTS_X + 1 * 18, EQUIP_Y }; // chestplate
            case 39 -> new int[]{ SLOTS_X + 0 * 18, EQUIP_Y }; // helmet
            case 40 -> new int[]{ SLOTS_X + 8 * 18, EQUIP_Y }; // offhand (far right)
            default -> null;
        };
    }
}