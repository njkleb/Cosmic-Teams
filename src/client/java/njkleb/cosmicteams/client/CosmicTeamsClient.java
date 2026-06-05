package njkleb.cosmicteams.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class CosmicTeamsClient implements ClientModInitializer {

	private static KeyBinding pingKey;
	private static KeyBinding configKey;

	// ── Hold-detection state ──────────────────────────────────────────────────
	private static boolean keyHeld       = false;
	private static long    keyDownTimeMs = 0;
	/** Minimum hold duration in ms before switching to aimed-ping mode. */
	private static final long HOLD_THRESHOLD_MS = 250;

	// ── Location-tracking state ───────────────────────────────────────────────
	private static String lastKnownWorld    = "";
	private static String lastKnownSubworld = "";

	// ── Extraction epoch reporting state ──────────────────────────────────────
	/**
	 * World key of the extraction world for which we last successfully sent an
	 * epoch to the server.  Prevents re-sending on every tick while in the same
	 * world, but resets when leaving extraction so a fresh epoch is sent on
	 * re-entry (either to the same world or a new one).
	 */
	private static String lastSentExtractionEpochWorld = "";

	@Override
	public void onInitializeClient() {

		ModSounds.register();

		ModUpdater.initialize();

		KeyBinding.Category pingCategory =
				KeyBinding.Category.create(Identifier.of("cosmicteams", "main"));

		// Ping key — default B
		pingKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.cosmicteams.ping",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_B,
				pingCategory
		));

		// Config key — unbound by default; assign in Controls if desired
		configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.cosmicteams.config",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_UNKNOWN,
				pingCategory
		));

		CosmicTeamsCommands.register();

		// Register GUI hooks (tooltip lore + slot overlays).
		GuiOverlayHandler.register();

		// ── World join ────────────────────────────────────────────────────────
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.player == null) return;

			lastKnownWorld    = "";
			lastKnownSubworld = "";

			CosmicTeamsConfig.initForCurrentPlayer();

			String mcServer = client.getCurrentServerEntry() != null
					? client.getCurrentServerEntry().address : "singleplayer";
			String world = client.world != null
					? client.world.getRegistryKey().getValue().toString() : "unknown";

			RelayClient.connect(mcServer, world);

			// Check for pending invites after auth completes.
			String playerName = client.player.getName().getString();
			String encoded    = URLEncoder.encode(playerName, StandardCharsets.UTF_8);
			new java.util.Timer().schedule(new java.util.TimerTask() {
				@Override public void run() {
					CosmicTeamsCommands.get("/pendinginvites?username=" + encoded, (status, json) -> {
						if (status != 200) return;
						int idx = 0;
						while ((idx = json.indexOf("\"teamname\":\"", idx)) >= 0) {
							idx += 12;
							int end = json.indexOf('"', idx);
							if (end < 0) break;
							String teamname = json.substring(idx, end);
							CosmicTeamsCommands.msg("§b[CosmicTeams] Pending invite to §f\"" + teamname +
									"\"§b — run §f/team join " + teamname + "§b to accept.");
							idx = end;
						}
					});
				}
			}, 3000);

			// Show any version-check notification that arrived while the player
			// was still in the main menu (mc.player was null at check time).
			ModUpdater.showPendingNotification();
		});

		// ── World disconnect ──────────────────────────────────────────────────
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			RelayClient.disconnect();
			RelayClient.activeBeacons.clear();
			BeaconRenderer.previewBeacon = null;
			keyHeld                        = false;
			DeathHandler.onDisconnect();
			lastKnownWorld                 = "";
			lastKnownSubworld              = "";
			lastSentExtractionEpochWorld   = "";
		});

		// ── Per-tick handling ─────────────────────────────────────────────────
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.world == null) return;

			// ── Config key ────────────────────────────────────────────────────
			// Opens the settings screen. Works even without a team or relay connection.
			if (configKey.wasPressed()) {
				client.setScreen(new CosmicTeamsConfigScreen(client.currentScreen));
				// Consume any additional queued presses
				while (configKey.wasPressed()) {}
			}

			// ── Key hold / ping ───────────────────────────────────────────────
			if (pingKey.wasPressed()) {
				if (!keyHeld) {
					keyHeld       = true;
					keyDownTimeMs = System.currentTimeMillis();
				}
				while (pingKey.wasPressed()) {}
			}

			if (keyHeld) {
				long heldMs = System.currentTimeMillis() - keyDownTimeMs;

				if (!pingKey.isPressed()) {
					keyHeld = false;

					if (!CosmicTeamsConfig.get().hasTeam()) {
						client.player.sendMessage(Text.literal(
								"§c[CosmicTeams] You are not on a team. " +
										"Use §f/team create <name>§c or wait for an invite."), false);
						BeaconRenderer.previewBeacon = null;
						return;
					}
					if (!RelayClient.isConnected()) {
						client.player.sendMessage(Text.literal(
								"§c[CosmicTeams] Not connected to relay server."), false);
						BeaconRenderer.previewBeacon = null;
						return;
					}

					String currentWorld = client.world.getRegistryKey().getValue().toString();
					String dimShort     = currentWorld.contains(":")
							? currentWorld.split(":")[1] : currentWorld;

					if (heldMs <= HOLD_THRESHOLD_MS) {
						// Quick tap — ping at player's own position.
						double x = client.player.getX();
						double y = client.player.getY();
						double z = client.player.getZ();
						RelayClient.sendLocationPing(x, y, z, currentWorld);
						client.player.sendMessage(Text.literal(
								buildPingSentMessage(x, y, z, dimShort)), false);

					} else {
						// Long hold — ping at the aimed block face.
						updatePreview(client);
						RelayClient.BeaconData preview = BeaconRenderer.previewBeacon;
						if (preview != null) {
							RelayClient.sendLocationPing(
									preview.x(), preview.y(), preview.z(), currentWorld);
							client.player.sendMessage(Text.literal(
									buildPingSentMessage(preview.x(), preview.y(), preview.z(),
											dimShort)), false);
						} else {
							client.player.sendMessage(Text.literal(
									"§c[CosmicTeams] No block in range to place ping."), false);
						}
					}

					BeaconRenderer.previewBeacon = null;

				} else if (heldMs > HOLD_THRESHOLD_MS) {
					updatePreview(client);
				}
			}

			// ── Auto-reconnect ────────────────────────────────────────────────
			{
				String mcSrv   = client.getCurrentServerEntry() != null
						? client.getCurrentServerEntry().address : "singleplayer";
				String curWorld = client.world.getRegistryKey().getValue().toString();
				if (RelayClient.tickReconnect(mcSrv, curWorld)) {
					lastKnownWorld    = "";
					lastKnownSubworld = "";
				}
			}

			// ── Location tracking ─────────────────────────────────────────────
			if (RelayClient.isConnected() && CosmicTeamsConfig.get().hasTeam()) {
				String currentWorld    = client.world.getRegistryKey().getValue().toString();
				String currentSubworld = RelayClient.getSubworld();

				if (!currentWorld.equals(lastKnownWorld)
						|| !currentSubworld.equals(lastKnownSubworld)) {
					lastKnownWorld    = currentWorld;
					lastKnownSubworld = currentSubworld;
					RelayClient.sendLocationUpdate(currentWorld, currentSubworld);
				}
			}

			// ── Extraction epoch reporting ────────────────────────────────────
			// When in an extraction world and the scoreboard timer is readable,
			// compute the epoch (when the 20:00 timer started) and send it once
			// per world entry so teammates can see estimated timers in the GUI.
			if (RelayClient.isConnected() && CosmicTeamsConfig.get().hasTeam()) {
				String currentWorld = client.world.getRegistryKey().getValue().toString();
				if (currentWorld.contains("extraction_map-")) {
					if (!currentWorld.equals(lastSentExtractionEpochWorld)) {
						// Scoreboard may not populate immediately on world entry —
						// retry each tick until we get a valid reading (> 0 secs).
						int secsLeft = GuiOverlayHandler.readExtractionSecondsFromScoreboard();
						if (secsLeft > 0 && secsLeft <= 20 * 60) {
							long epochMs = System.currentTimeMillis()
									- (20L * 60 - secsLeft) * 1_000L;
							RelayClient.sendExtractionEpoch(epochMs);
							lastSentExtractionEpochWorld = currentWorld;
						}
					}
				} else {
					// Reset so a fresh epoch is sent on re-entry.
					lastSentExtractionEpochWorld = "";
				}
			}
		});

		System.out.println("[CosmicTeams] Initialized.");
	}

	/**
	 * Builds the ping-sent chat message shown to the local player after they
	 * successfully fire a ping.
	 *
	 * <p>Format depends on {@link CosmicTeamsConfig.Settings#compactPingMessages}:
	 * <ul>
	 *   <li>Normal:  {@code §aPinged in §f<World> (subworld) §aat §f<x>, <y>, <z>}</li>
	 *   <li>Compact: {@code §aPinged at §f<x>, <y>, <z>}</li>
	 * </ul>
	 */
	private static String buildPingSentMessage(double x, double y, double z, String dimShort) {
		if (CosmicTeamsConfig.get().settings.compactPingMessages) {
			return String.format("§aPinged at §f%d, %d, %d",
					Math.round(x), Math.round(y), Math.round(z));
		}
		String subworld     = RelayClient.getSubworld();
		String worldDisplay = WorldNames.displayName(dimShort, subworld);  // ← fixed
		String subworldPart = subworld.isEmpty() ? "" : " §b(" + subworld + ")§r";
		return String.format("§aPinged in §f%s%s §aat §f%d, %d, %d",
				worldDisplay, subworldPart,
				Math.round(x), Math.round(y), Math.round(z));
	}

	/**
	 * Raycasts from the player's eye position using the range configured in
	 * {@link CosmicTeamsConfig.Settings#aimPingRange}.
	 * Places the preview beacon at the block-face intersection.
	 * Clears the preview if nothing is hit.
	 */
	private static void updatePreview(MinecraftClient client) {
		if (client.player == null || client.world == null) {
			BeaconRenderer.previewBeacon = null;
			return;
		}

		// Use the configured range instead of the old hardcoded 500 blocks
		double range = CosmicTeamsConfig.get().settings.aimPingRange;
		HitResult hit = client.player.raycast(range, 1.0f, false);

		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockHitResult blockHit = (BlockHitResult) hit;
			Vec3d  pos      = blockHit.getPos();
			String world    = client.world.getRegistryKey().getValue().toString();
			String subworld = RelayClient.getSubworld();
			BeaconRenderer.previewBeacon = new RelayClient.BeaconData(
					"", pos.x, pos.y, pos.z, world, subworld,
					System.currentTimeMillis(), 0xFFFFFF);
		} else {
			BeaconRenderer.previewBeacon = null;
		}
	}
}