package njkleb.cosmicteams.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerLikeState;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Renders the text label component of ping beacons as billboarded world-space
 * overlays, called from the same injection site as {@link BeaconRenderer}.
 *
 * <h2>Coordinate space</h2>
 * Builds the camera-view {@link MatrixStack} identically to {@link BeaconRenderer}
 * (pitch then yaw+180), translates to the beacon position in that space, then
 * undoes the camera rotation to produce a billboard facing the player.  All
 * labels share one matrix stack for the frame; each beacon does push/pop.
 *
 * <h2>Constant screen-size scaling</h2>
 * Scale = {@link #TEXT_SCALE_BASE} × renderDist.  Combined with the perspective
 * 1/distance factor this keeps apparent pixel size constant at all ranges.
 *
 * <h2>Fog avoidance</h2>
 * TextRenderer's built-in pipelines bind fog uniforms; at distances beyond the
 * render horizon the text is fully fogged out even with SEE_THROUGH.
 * {@link BeaconRenderer} avoids this because its custom pipeline omits fog.
 * Rather than fighting the shader, each label is physically rendered at most
 * {@link #MAX_RENDER_DIST} blocks from the camera along the correct direction.
 * Scale is derived from renderDist, not true camDist, so apparent size is
 * unchanged.  No fog → no clip, regardless of how far the beacon is.
 *
 * <h2>Depth / occlusion</h2>
 * {@link TextRenderer.TextLayerType#SEE_THROUGH} disables depth testing so
 * labels are always fully visible even when the beacon is behind terrain.
 *
 * <h2>View-bobbing and zoom cancellation</h2>
 * {@code GameRenderer.renderWorld()} post-multiplies the projection matrix by a
 * distortion matrix B built from {@code tiltViewWhenHurt} and (when enabled)
 * {@code bobView}, giving {@code ProjMat = perspective(fov) × B}.
 * TextRenderer's shaders consume this global {@code ProjMat}, so the text would
 * otherwise bob and — critically — shift whenever an FOV-modifying system such
 * as OptiFine zoom changes {@code fov} independently of B.
 *
 * <p>To fix both issues we reconstruct B by replicating the exact same
 * {@link MatrixStack} logic that {@code renderWorld()} uses (reading the same
 * game state at the same tick-progress), then seed our own {@link MatrixStack}
 * with {@code B⁻¹}. The result is:
 * <pre>
 *   ProjMat × B⁻¹ × … = perspective(fov) × B × B⁻¹ × … = perspective(fov) × …
 * </pre>
 * The bob and tilt cancel out completely, and the correct zoomed {@code fov} is
 * preserved, so text stays anchored in world space under all conditions.
 *
 * <h2>Label format</h2>
 * <pre>
 *   Username          ← bold, color from nameColor parameter (cfg.labelNameColor
 *                       for regular beacons, cfg.deathLabelNameColor for death beacons)
 *   50m • 16s         ← distance in {@code labelDistColor} (if showDistLabel),
 *                       age in {@code labelAgeColor} (if showAgeLabel).
 *                       Either part is omitted independently; if both are off
 *                       the second line is suppressed entirely.
 *                       Preview beacons always show distance regardless of settings.
 * </pre>
 * Visibility of each component controlled by {@code showNameLabel} /
 * {@code showDistLabel} / {@code showAgeLabel}.
 *
 * <h2>XYZ proximity fade</h2>
 * Beyond 10 blocks: fully opaque.  Within 5 blocks: holds at 50%.
 *
 * <h2>Call site</h2>
 * Add {@code BeaconHudRenderer.render(camera)} immediately after
 * {@code BeaconRenderer.render(camera)} in the existing injection mixin.
 * No separate registration needed.  {@code InGameHudMixin} should be removed.
 */
public class BeaconHudRenderer {

    // ── Proximity fade ────────────────────────────────────────────────────────

    private static final double FADE_START_XYZ = 10.0;
    private static final double FADE_END_XYZ   = 5.0;

    // ── World-space text sizing ───────────────────────────────────────────────

    /**
     * Base scale factor.  Multiplied by {@link #MAX_RENDER_DIST} (or true
     * camDist when closer than that) to produce a constant apparent pixel size.
     * 0.025 / 8 is half the previous size (0.025 / 4).
     */
    private static final float TEXT_SCALE_BASE = 0.025f / 8f; // 0.003125

    /** Line separation in text-renderer units (before world-space scale). */
    private static final float LINE_HEIGHT = 10f;

    /**
     * Maximum distance from the camera at which text quads are actually placed.
     * Labels for beacons further than this are rendered in the correct direction
     * but clamped to this distance, keeping them inside the fog-free zone.
     * Scale is derived from renderDist so apparent size stays constant.
     */
    private static final double MAX_RENDER_DIST = 10.0;

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Renders all active beacon labels.
     * Call immediately after {@link BeaconRenderer#render(Camera)} in the
     * same mixin injection.
     */
    public static void render(Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long   now        = System.currentTimeMillis();
        CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
        TextRenderer tr         = mc.textRenderer;
        Vec3d        camPos     = camera.getCameraPos();
        Vec3d        playerPos  = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        String       mySubworld = RelayClient.getSubworld();

        // ── View-distortion cancellation ──────────────────────────────────────
        //
        // GameRenderer.renderWorld() builds a MatrixStack B from tiltViewWhenHurt
        // and bobView, then does: projectionMatrix.mul(B) so ProjMat = P × B.
        // TextRenderer's shaders apply ProjMat to our text quads, causing bobbing
        // and — when an external system like OptiFine changes the FOV — a position
        // offset, because deriving B from the passed projection matrices conflates
        // the FOV change with the bob component.
        //
        // The correct fix is to reproduce B from live game state (same logic, same
        // tick-progress) and seed our MatrixStack with B⁻¹ so the shader sees:
        //   ProjMat × B⁻¹ × … = P × B × B⁻¹ × … = P × … (bob gone, FOV preserved)
        Matrix4f bobCancel = computeBobCancellation(mc, camera);

        // Build the camera-view matrix identically to BeaconRenderer so that
        // per-beacon translations are in the exact same coordinate space.
        // BeaconRenderer: ms.multiply(Rx pitch) then ms.multiply(Ry yaw+180).
        MatrixStack ms = new MatrixStack();
        ms.peek().getPositionMatrix().set(bobCancel);
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));

        // Shared with BeaconRenderer; entity consumers support all TextRenderer layers.
        // BeaconRenderer already flushed its beam layer before this call, so only
        // text layers will be pending when we call immediate.draw() below.
        VertexConsumerProvider.Immediate immediate =
                mc.getBufferBuilders().getEntityVertexConsumers();

        // ── Active beacons ────────────────────────────────────────────────────
        for (RelayClient.BeaconData b : RelayClient.activeBeacons) {
            if (now - b.timestamp() > BeaconRenderer.getLifetimeMs()) continue;
            if (!RelayClient.worldMatches(b.world(), b.subworld(), mySubworld)) continue;

            double dist = playerPos.distanceTo(new Vec3d(b.x(), b.y(), b.z()));
            float  fade = xyzFade(dist);
            if (fade <= 0f) continue;

            long ageSeconds = (now - b.timestamp()) / 1000L;

            drawLabel(ms, immediate, tr, camera, camPos,
                    b.x(), b.y() + 1.5, b.z(),
                    b.player(), dist, ageSeconds, false, fade, cfg, cfg.labelNameColor);
        }

        // ── Death beacon labels ───────────────────────────────────────────────
        // Rendered separately from regular beacons so they can use a different
        // name color (cfg.deathLabelNameColor).  No deduplication — all active
        // death beacons within the display lifetime are shown.
        long deathLifetimeMs = cfg.deathBeaconLifetimeMs();
        for (RelayClient.BeaconData b : RelayClient.activeDeathBeacons) {
            if (now - b.timestamp() > deathLifetimeMs) continue;
            if (!RelayClient.worldMatches(b.world(), b.subworld(), mySubworld)) continue;

            double dist = playerPos.distanceTo(new Vec3d(b.x(), b.y(), b.z()));
            float  fade = xyzFade(dist);
            if (fade <= 0f) continue;

            long ageSeconds = (now - b.timestamp()) / 1000L;

            drawLabel(ms, immediate, tr, camera, camPos,
                    b.x(), b.y() + 1.5, b.z(),
                    b.player(), dist, ageSeconds, false, fade, cfg, cfg.deathLabelNameColor);
        }

        // ── Preview beacon ────────────────────────────────────────────────────
        RelayClient.BeaconData preview = BeaconRenderer.previewBeacon;
        if (preview != null) {
            double dist = playerPos.distanceTo(
                    new Vec3d(preview.x(), preview.y(), preview.z()));
            float fade = xyzFade(dist);
            if (fade > 0f) {
                drawLabel(ms, immediate, tr, camera, camPos,
                        preview.x(), preview.y() + 1.5, preview.z(),
                        "", dist, 0, true, fade, cfg, cfg.labelNameColor);
            }
        }

        immediate.draw();
    }

    // ── Bob cancellation ──────────────────────────────────────────────────────

    /**
     * Replicates the distortion {@link MatrixStack} that
     * {@code GameRenderer.renderWorld()} builds and post-multiplies onto the
     * projection matrix, then returns its inverse.
     *
     * <p>The two sources of distortion, applied in the same order as vanilla:
     * <ol>
     *   <li>{@code tiltViewWhenHurt} — always evaluated; usually identity unless
     *       the camera entity is taking damage or dying.</li>
     *   <li>{@code bobView} — only applied when the "View Bobbing" option is on.</li>
     * </ol>
     *
     * <p>Reading from live game state at the same {@code tickProgress} that
     * {@code renderWorld()} used ensures the reconstructed matrix is numerically
     * identical to the one baked into {@code ProjMat}.
     *
     * <p>When neither source applies the stack remains identity and
     * {@code invert()} returns identity, so there is no cost or side-effect when
     * both effects are absent.
     */
    private static Matrix4f computeBobCancellation(MinecraftClient mc, Camera camera) {
        float       tickProgress = camera.getLastTickProgress();
        Entity      cameraEntity = mc.getCameraEntity();
        MatrixStack bobStack     = new MatrixStack();

        // ── Mirror of GameRenderer.tiltViewWhenHurt() ─────────────────────────
        if (cameraEntity instanceof LivingEntity livingEntity) {
            float f = (float) livingEntity.hurtTime - tickProgress;

            if (livingEntity.isDead()) {
                float g = Math.min((float) livingEntity.deathTime + tickProgress, 20.0F);
                bobStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(
                        40.0F - 8000.0F / (g + 200.0F)));
            }

            if (f >= 0.0F) {
                f /= (float) livingEntity.maxHurtTime;
                f  = MathHelper.sin(f * f * f * f * 3.1415927F);
                float g = livingEntity.getDamageTiltYaw();
                float h = (float)((double)(-f) * 14.0
                        * (Double) mc.options.getDamageTiltStrength().getValue());
                bobStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-g));
                bobStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(h));
                bobStack.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(g));
            }
        }

        // ── Mirror of GameRenderer.bobView() ──────────────────────────────────
        if ((Boolean) mc.options.getBobView().getValue()
                && cameraEntity instanceof AbstractClientPlayerEntity player) {
            ClientPlayerLikeState state = player.getState();
            float f = state.getReverseLerpedDistanceMoved(tickProgress);
            float g = state.lerpMovement(tickProgress);
            bobStack.translate(
                    MathHelper.sin(f * 3.1415927F) * g * 0.5F,
                    -Math.abs(MathHelper.cos(f * 3.1415927F) * g),
                    0.0F);
            bobStack.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(
                    MathHelper.sin(f * 3.1415927F) * g * 3.0F));
            bobStack.multiply(RotationAxis.POSITIVE_X.rotationDegrees(
                    Math.abs(MathHelper.cos(f * 3.1415927F - 0.2F) * g) * 5.0F));
        }

        return new Matrix4f(bobStack.peek().getPositionMatrix()).invert();
    }

    // ── Label drawing ─────────────────────────────────────────────────────────

    /**
     * Draws up to two centred lines for one beacon.
     *
     * <p>Line 1: player name — shown when {@code showNameLabel} is true, not a
     * preview, and the player string is non-empty.</p>
     *
     * <p>Line 2: distance / age — each component is independently toggled by
     * {@code showDistLabel} and {@code showAgeLabel}.  All four combinations are
     * handled: both on ("50m • 16s"), dist only ("50m"), age only ("16s"), both
     * off (line 2 is suppressed entirely).  Preview beacons always display
     * distance regardless of {@code showDistLabel}.</p>
     *
     * <p>If nothing at all would be drawn (name off, dist off, age off, not a
     * preview) the method returns early without pushing the matrix stack.</p>
     *
     * @param nameColor  RGB int for the player name line.  Pass
     *                   {@code cfg.labelNameColor} for regular beacons and
     *                   {@code cfg.deathLabelNameColor} for death beacons.
     */
    private static void drawLabel(MatrixStack ms,
                                  VertexConsumerProvider.Immediate immediate,
                                  TextRenderer tr,
                                  Camera camera, Vec3d camPos,
                                  double wx, double wy, double wz,
                                  String player, double dist, long ageSeconds,
                                  boolean isPreview, float fade,
                                  CosmicTeamsConfig.Settings cfg, int nameColor) {

        boolean showName       = !isPreview && !player.isEmpty() && cfg.showNameLabel;
        boolean showDist       = isPreview || cfg.showDistLabel;
        boolean showAge        = !isPreview && cfg.showAgeLabel;
        boolean showSecondLine = showDist || showAge;

        if (!showName && !showSecondLine) return;

        int lineCount = (showName ? 1 : 0) + (showSecondLine ? 1 : 0);

        double dx = wx - camPos.x;
        double dy = wy - camPos.y;
        double dz = wz - camPos.z;
        double camDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (camDist < 0.001) return;

        double renderDist = Math.min(camDist, MAX_RENDER_DIST);
        double t          = renderDist / camDist;
        float  scale      = TEXT_SCALE_BASE * (float) renderDist;

        ms.push();

        ms.translate(dx * t, dy * t, dz * t);
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(-(camera.getYaw() + 180.0F)));
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(-camera.getPitch()));
        ms.scale(scale, -scale, scale);

        Matrix4f mat    = ms.peek().getPositionMatrix();
        int      light  = LightmapTextureManager.MAX_LIGHT_COORDINATE;
        float    startY = -(lineCount * LINE_HEIGHT) / 2f;
        int      lineIdx = 0;

        // Semi-transparent dark backing, faded in sync with the text.
        // ~55% opacity at full visibility; softens to ~27% at the proximity minimum (fade=0.5).
        int bg = argb(0x000000, fade * 0.55f);

        // ── Line 1: player name ────────────────────────────────────────────────────
        if (showName) {
            float ty       = startY + lineIdx * LINE_HEIGHT;
            Text  boldName = Text.literal(player).styled(s -> s.withBold(true));
            float nameW    = tr.getWidth(boldName);
            tr.draw(boldName, -nameW / 2f, ty,
                    argb(nameColor, fade), true,          // shadow = true
                    mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);
            lineIdx++;
        }

        // ── Line 2: distance and/or age ───────────────────────────────────────────
        if (showSecondLine) {
            float ty = startY + lineIdx * LINE_HEIGHT;

            if (isPreview) {
                String s = Math.round(dist) + "m";
                tr.draw(s, -tr.getWidth(s) / 2f, ty,
                        argb(cfg.labelDistColor, fade), true,
                        mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);

            } else if (showDist && showAge) {
                // Two adjacent draws — their background quads will be flush and read as one strip.
                String distPart = Math.round(dist) + "m • ";
                String agePart  = ageSeconds + "s";
                float  distW    = tr.getWidth(distPart);
                float  ageW     = tr.getWidth(agePart);
                float  tx       = -(distW + ageW) / 2f;
                tr.draw(distPart, tx,         ty,
                        argb(cfg.labelDistColor, fade), true,
                        mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);
                tr.draw(agePart,  tx + distW, ty,
                        argb(cfg.labelAgeColor,  fade), true,
                        mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);

            } else if (showDist) {
                String s = Math.round(dist) + "m";
                tr.draw(s, -tr.getWidth(s) / 2f, ty,
                        argb(cfg.labelDistColor, fade), true,
                        mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);

            } else {
                String s = ageSeconds + "s";
                tr.draw(s, -tr.getWidth(s) / 2f, ty,
                        argb(cfg.labelAgeColor, fade), true,
                        mat, immediate, TextRenderer.TextLayerType.SEE_THROUGH, bg, light);
            }
        }

        ms.pop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Packs an RGB value and a 0–1 fade multiplier into an ARGB int. */
    private static int argb(int rgb, float fade) {
        return (Math.round(0xFF * fade) << 24) | (rgb & 0x00_FFFFFF);
    }

    /** Returns a 0.5–1 fade multiplier based on 3D distance. */
    private static float xyzFade(double dist) {
        if (dist <= FADE_END_XYZ)   return 0.5f;
        if (dist >= FADE_START_XYZ) return 1f;
        return 0.5f + 0.5f * (float) ((dist - FADE_END_XYZ)
                / (FADE_START_XYZ - FADE_END_XYZ));
    }
}