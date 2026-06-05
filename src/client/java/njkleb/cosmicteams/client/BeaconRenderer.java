package njkleb.cosmicteams.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.*;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Renders the 3D beam component of ping beacons.

 * Each beacon draws two concentric square pillars (inner + outer), giving a
 * bright core with a softer glow — without any extra render-layer overhead
 * since both share the same VertexConsumer in the same draw call.

 * The beam extends from the void floor to the sky limit in both directions.
 *
 * <h2>Config-driven values</h2>
 * <ul>
 *   <li>Beam lifetime  — {@link CosmicTeamsConfig.Settings#beaconLifetimeSecs}</li>
 *   <li>Inner alpha    — {@link CosmicTeamsConfig.Settings#innerBeamAlpha}</li>
 *   <li>Outer alpha    — {@link CosmicTeamsConfig.Settings#outerBeamAlpha}</li>
 *   <li>Beam color     — {@link CosmicTeamsConfig.Settings#effectiveBeaconColor(String)}</li>
 *   <li>Death lifetime — {@link CosmicTeamsConfig.Settings#deathBeaconLifetimeSecs}</li>
 *   <li>Death color    — {@link CosmicTeamsConfig.Settings#deathBeaconColor}</li>
 * </ul>
 *
 * <h2>Storage vs. display lifetime</h2>
 * Beacons are stored in {@link RelayClient#activeBeacons} for the full
 * {@link RelayClient#RECENT_PING_LIFETIME_MS} (5 minutes), regardless of what
 * the config lifetime is set to.  Each render pass simply skips beacons whose
 * age exceeds the current config value — it never removes them from the list.
 * This means that if a player lowers the display lifetime and then raises it
 * again, any beacon that is still within the storage window will reappear
 * correctly without needing to be re-sent.

 * Death beacons follow the same principle but use {@link RelayClient#DEATH_PING_STORAGE_MS}
 * (10 minutes) as their hard storage cap, matching the maximum configurable display
 * lifetime of 600 seconds.

 * XZ proximity fade: beam alpha scales linearly from full opacity at
 * 20 blocks XZ distance down to 0 at 10 blocks XZ distance.

 * World/subworld filtering: a beacon beam is only rendered when the local
 * player is in the same dimension AND the same subworld as the sender.
 * See {@link RelayClient#worldMatches} for the exact rules.
 */
public class BeaconRenderer {

    // ── Beam geometry ─────────────────────────────────────────────────────────

    /** Beam extends this many blocks above the ping position. */
    private static final float BEAM_HEIGHT_UP   = 2048f;

    /** Beam extends this many blocks below the ping position (into the void). */
    private static final float BEAM_HEIGHT_DOWN = 2048f;

    /** Half-width of the inner (bright) beam column. */
    private static final float INNER_HALF  = 0.10f;

    /** Half-width of the outer (glow) beam column. */
    private static final float OUTER_HALF  = 0.30f;

    /** Alpha multiplier applied to both beams for the preview beacon. */
    private static final float PREVIEW_ALPHA_SCALE = 0.75f;

    // ── XZ proximity fade distances ───────────────────────────────────────────

    /** Beyond this XZ distance the beam is fully opaque (no fade). */
    private static final double FADE_START_XZ = 20.0;

    /** Within this XZ distance the beam holds at 50% opacity. */
    private static final double FADE_END_XZ   = 10.0;

    // ── Custom render layer ───────────────────────────────────────────────────

    private static RenderLayer beamLayer;

    private static RenderLayer getBeamLayer() {
        if (beamLayer == null) {
            RenderPipeline pipeline = RenderPipelines.register(
                    RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                            .withLocation("cosmicteams/beacon_beam")
                            .withCull(false)
                            .build()
            );
            RenderSetup setup = RenderSetup.builder(pipeline)
                    .expectedBufferSize(8192)
                    .build();
            beamLayer = RenderLayer.of("cosmicteams_beacon_beam", setup);
        }
        return beamLayer;
    }

    // ── Public state ──────────────────────────────────────────────────────────

    public static volatile RelayClient.BeaconData previewBeacon = null;

    // ── Lifetime accessor (used by BeaconHudRenderer) ─────────────────────────

    /**
     * Returns the current beacon display lifetime in milliseconds from config.
     * Call this instead of referencing a hardcoded constant so that the
     * config value is always authoritative for rendering decisions.
     */
    public static long getLifetimeMs() {
        return CosmicTeamsConfig.get().settings.beaconLifetimeMs();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void render(Camera camera) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) return;

        long now         = System.currentTimeMillis();
        long lifetimeMs  = getLifetimeMs();

        // Evict entries that have exceeded the hard storage cap (5 minutes).
        // This is intentionally NOT the config lifetime — we keep beacons in the
        // list for the full storage window so that raising the display lifetime
        // after a temporary reduction can bring them back into view.
        RelayClient.activeBeacons.removeIf(
                b -> now - b.timestamp() > RelayClient.RECENT_PING_LIFETIME_MS);

        // Evict death beacons that have exceeded the hard storage cap (10 minutes).
        RelayClient.activeDeathBeacons.removeIf(
                b -> now - b.timestamp() > RelayClient.DEATH_PING_STORAGE_MS);

        CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
        Vec3d cam    = camera.getCameraPos();
        Vec3d player = new Vec3d(
                mc.player.getX(), mc.player.getY(), mc.player.getZ());

        MatrixStack ms = new MatrixStack();
        ms.multiply(RotationAxis.POSITIVE_X.rotationDegrees(camera.getPitch()));
        ms.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(camera.getYaw() + 180.0F));

        VertexConsumerProvider.Immediate immediate =
                mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer vc = immediate.getBuffer(getBeamLayer());

        String mySubworld = RelayClient.getSubworld();

        // ── Active beacons ────────────────────────────────────────────────────
        for (RelayClient.BeaconData b : RelayClient.activeBeacons) {
            // Skip beacons that are older than the current display lifetime.
            // The entry is kept in the list — if the user raises the lifetime
            // setting later, the beacon will reappear as long as it is still
            // within the 5-minute storage window.
            if (now - b.timestamp() > lifetimeMs) continue;

            if (!RelayClient.worldMatches(b.world(), b.subworld(), mySubworld)) continue;

            double xzDist = Math.sqrt(
                    (player.x - b.x()) * (player.x - b.x()) +
                            (player.z - b.z()) * (player.z - b.z()));
            float fade = xzFade(xzDist);
            if (fade <= 0f) continue;

            // Determine color: own beacons may use a custom color
            int col   = cfg.effectiveBeaconColor(b.player());
            int red   = (col >> 16) & 0xFF;
            int green = (col >>  8) & 0xFF;
            int blue  =  col        & 0xFF;

            double rx = b.x() - cam.x;
            double ry = b.y() - cam.y;
            double rz = b.z() - cam.z;

            // Outer beam first (drawn behind inner due to no depth write)
            drawBeam(ms, vc, rx, ry, rz, red, green, blue,
                    scaled(cfg.outerBeamAlpha, fade), OUTER_HALF);
            // Inner beam on top
            drawBeam(ms, vc, rx, ry, rz, red, green, blue,
                    scaled(cfg.innerBeamAlpha, fade), INNER_HALF);
        }

        // ── Death beacons ─────────────────────────────────────────────────────
        // Multiple death beacons can be active per player simultaneously — no
        // deduplication.  They use their own color from config rather than the
        // per-sender effectiveBeaconColor logic, so all death beacons look alike
        // regardless of whose they are.
        long deathLifetimeMs = cfg.deathBeaconLifetimeMs();
        int  deathCol = cfg.deathBeaconColor;
        int  dr = (deathCol >> 16) & 0xFF;
        int  dg = (deathCol >>  8) & 0xFF;
        int  db =  deathCol        & 0xFF;

        for (RelayClient.BeaconData b : RelayClient.activeDeathBeacons) {
            if (now - b.timestamp() > deathLifetimeMs) continue;
            if (!RelayClient.worldMatches(b.world(), b.subworld(), mySubworld)) continue;

            double xzDist = Math.sqrt(
                    (player.x - b.x()) * (player.x - b.x()) +
                            (player.z - b.z()) * (player.z - b.z()));
            float fade = xzFade(xzDist);
            if (fade <= 0f) continue;

            double rx = b.x() - cam.x;
            double ry = b.y() - cam.y;
            double rz = b.z() - cam.z;

            drawBeam(ms, vc, rx, ry, rz, dr, dg, db,
                    scaled(cfg.outerBeamAlpha, fade), OUTER_HALF);
            drawBeam(ms, vc, rx, ry, rz, dr, dg, db,
                    scaled(cfg.innerBeamAlpha, fade), INNER_HALF);
        }

        // ── Preview beacon ────────────────────────────────────────────────────
        RelayClient.BeaconData preview = previewBeacon;
        if (preview != null) {
            double xzDist = Math.sqrt(
                    (player.x - preview.x()) * (player.x - preview.x()) +
                            (player.z - preview.z()) * (player.z - preview.z()));
            float fade = xzFade(xzDist);
            if (fade > 0f) {
                // Preview uses own-beacon color if custom color is enabled, else white
                int previewCol = cfg.useCustomOwnColor ? cfg.ownBeaconColor : 0xFFFFFF;
                int pr = (previewCol >> 16) & 0xFF;
                int pg = (previewCol >>  8) & 0xFF;
                int pb =  previewCol        & 0xFF;

                double rx = preview.x() - cam.x;
                double ry = preview.y() - cam.y;
                double rz = preview.z() - cam.z;

                drawBeam(ms, vc, rx, ry, rz, pr, pg, pb,
                        scaled((int)(cfg.outerBeamAlpha * PREVIEW_ALPHA_SCALE), fade), OUTER_HALF);
                drawBeam(ms, vc, rx, ry, rz, pr, pg, pb,
                        scaled((int)(cfg.innerBeamAlpha * PREVIEW_ALPHA_SCALE), fade), INNER_HALF);
            }
        }

        immediate.draw(getBeamLayer());
    }

    // ── Geometry ──────────────────────────────────────────────────────────────

    private static void drawBeam(MatrixStack ms, VertexConsumer vc,
                                 double rx, double ry, double rz,
                                 int red, int green, int blue, int alpha,
                                 float half) {
        if (alpha <= 0) return;

        ms.push();
        ms.translate(rx, ry, rz);
        Matrix4f mat = ms.peek().getPositionMatrix();

        float top = BEAM_HEIGHT_UP;
        float bot = -BEAM_HEIGHT_DOWN;

        // North face (−Z)
        vc.vertex(mat,  half, top, -half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, top, -half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, bot, -half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, bot, -half).color(red, green, blue, alpha);

        // South face (+Z)
        vc.vertex(mat, -half, top,  half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, top,  half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, bot,  half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, bot,  half).color(red, green, blue, alpha);

        // West face (−X)
        vc.vertex(mat, -half, top,  half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, top, -half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, bot, -half).color(red, green, blue, alpha);
        vc.vertex(mat, -half, bot,  half).color(red, green, blue, alpha);

        // East face (+X)
        vc.vertex(mat,  half, top, -half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, top,  half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, bot,  half).color(red, green, blue, alpha);
        vc.vertex(mat,  half, bot, -half).color(red, green, blue, alpha);

        ms.pop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns a 0.5..1 fade multiplier based on XZ distance.
     * 0.5 = half opacity (≤ FADE_END_XZ), 1 = full opacity (≥ FADE_START_XZ).
     */
    private static float xzFade(double xzDist) {
        if (xzDist <= FADE_END_XZ)   return 0.5f;
        if (xzDist >= FADE_START_XZ) return 1f;
        return 0.5f + 0.5f * (float) ((xzDist - FADE_END_XZ) / (FADE_START_XZ - FADE_END_XZ));
    }

    /** Multiplies a 0–255 alpha by a 0–1 fade multiplier and returns a clamped int. */
    private static int scaled(int alpha, float fade) {
        return Math.max(0, Math.min(255, Math.round(alpha * fade)));
    }
}