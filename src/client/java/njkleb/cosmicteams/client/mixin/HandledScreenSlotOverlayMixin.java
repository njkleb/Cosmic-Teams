package njkleb.cosmicteams.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import njkleb.cosmicteams.client.GuiOverlayHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into HandledScreen.drawSlot() to render CosmicTeams adventure-GUI
 * number overlays immediately after each slot's item icon is drawn.
 *
 * ── Why no guiLeft / guiTop ───────────────────────────────────────────────────
 * HandledScreen.renderMain() pushes a matrix and calls
 *   context.getMatrices().translate(this.x, this.y)
 * BEFORE invoking drawSlots() → drawSlot().  By the time our @RETURN hook
 * fires, the draw context is already in GUI-local space, so slot.x and slot.y
 * are the correct drawing coordinates directly — adding this.x / this.y again
 * would double-offset everything and push the numbers far off-screen.
 *
 * ── Layering guarantee ────────────────────────────────────────────────────────
 * drawMouseoverTooltip() runs after all slots finish in HandledScreen.render(),
 * so text drawn here is naturally behind any open tooltip — no Z tricks needed.
 */
@Mixin(HandledScreen.class)
public class HandledScreenSlotOverlayMixin {

    /**
     * Fires at the end of every drawSlot() call.
     * Delegates to AdventureGuiHandler so all business logic stays out of the mixin.
     */
    @Inject(method = "drawSlot", at = @At("RETURN"))
    private void onDrawSlot(DrawContext context, Slot slot, int mouseX, int mouseY, CallbackInfo ci) {
        GuiOverlayHandler.renderSlotOverlay(context, slot);
    }
}