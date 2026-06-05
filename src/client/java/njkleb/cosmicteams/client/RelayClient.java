package njkleb.cosmicteams.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.session.Session;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class RelayClient {

    // =========================================================
    //  EDIT THIS before building and distributing the mod
    // =========================================================
    public static final String SERVER_URL = "ws://150.136.178.169:8080";
    // =========================================================

    private static WebSocket socket;
    private static volatile boolean connected  = false;
    private static final AtomicBoolean connecting = new AtomicBoolean(false);
    private static volatile long disconnectedAtMs       = -1L;
    private static volatile long lastReconnectAttemptMs =  0L;
    private static final long    INITIAL_RECONNECT_MS   =  5_000L;
    private static final long    RECONNECT_INTERVAL_MS  = 30_000L;

    /** Thread-safe list of active beacons — iterated from the render thread. */
    public static final CopyOnWriteArrayList<BeaconData> activeBeacons =
            new CopyOnWriteArrayList<>();

    /**
     * Thread-safe list of active death beacons — iterated from the render thread.
     *
     * <p>Unlike {@link #activeBeacons}, entries are never deduplicated by player.
     * A player can accumulate multiple death beacons simultaneously (one per death
     * within the storage window), so incoming entries are always appended.</p>
     */
    public static final CopyOnWriteArrayList<BeaconData> activeDeathBeacons =
            new CopyOnWriteArrayList<>();

    /**
     * Most recent ping from each player, retained for up to {@value #RECENT_PING_LIFETIME_MS} ms
     * regardless of the beacon render lifetime configured in settings.
     *
     * <p>Key: player name. Value: most recent {@link BeaconData} from that player.</p>
     *
     * <p>This store is updated whenever a beacon is received or sent, and is
     * queried by {@link #hasRecentPingInSubworld} for the adventure GUI indicator.
     * Entries are pruned lazily inside that method.</p>
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, BeaconData> recentPings =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** How long the most recent ping from each player is retained (5 minutes). */
    public static final long RECENT_PING_LIFETIME_MS = 5 * 60 * 1_000L;

    /**
     * Hard storage cap for death beacons, matching the maximum configurable display
     * lifetime of 600 seconds (10 minutes).  Beacons are kept in the list for the
     * full storage window so that raising the display lifetime after a temporary
     * reduction can bring older entries back into view.
     */
    public static final long DEATH_PING_STORAGE_MS = 10 * 60 * 1_000L;

    /** Names of all known teammates, updated live via member_joined / member_left. */
    public static final java.util.Set<String> teammateNames =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Current subworld of each online teammate on the same MC server.
     * Key: username. Value: subworld name (empty string = not in a subworld).
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, String> teammateSubworlds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Current Minecraft world registry key of each online teammate on the same
     * MC server (e.g. {@code "cosmicpvp:adventure_ruins_facility-0"}).
     * Key: username. Value: full world key (empty string if unknown).
     *
     * <p>Populated alongside {@link #teammateSubworlds} by
     * {@link #fetchTeamLocations()}, which reads the {@code world} field now
     * included in the {@code /teamlocations} response.
     */
    public static final java.util.concurrent.ConcurrentHashMap<String, String> teammateWorlds =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Extraction world-start epochs reported by teammates, fetched from the
     * server via {@link #fetchExtractionEpochs()}.  Each value is a Unix
     * timestamp (ms) representing the moment a particular extraction world's
     * 20:00 countdown began.  Used by {@code GuiOverlayHandler.ExtractionsDef}
     * to estimate current timer values without reading the local scoreboard.
     */
    public static final CopyOnWriteArrayList<Long> extractionEpochs =
            new CopyOnWriteArrayList<>();

    private static final java.util.Set<UUID> SUBWORLD_CANDIDATE_UUIDS = java.util.Set.of(
            UUID.fromString("00000000-0000-0000-0000-00000000002b"),
            UUID.fromString("00000000-0000-0000-0000-000000000040")
    );

    public record BeaconData(String player, double x, double y, double z,
                             String world, String subworld, long timestamp, int color) {}

    // ── Subworld helpers ──────────────────────────────────────────────────────

    public static String getSubworld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return "";
        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            if (!SUBWORLD_CANDIDATE_UUIDS.contains(entry.getProfile().id())) continue;
            Text dn = entry.getDisplayName();
            if (dn == null) continue;
            String raw = dn.getString();
            if (raw.startsWith("§7")) {
                return raw.replaceAll("§[0-9a-fA-Fk-oK-OrRlLmMnN]", "").trim();
            }
        }
        return "";
    }

    public static boolean worldMatches(String beaconWorld, String beaconSubworld,
                                       String mySubworld) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return false;
        if (!mc.world.getRegistryKey().getValue().toString().equals(beaconWorld)) return false;
        return beaconSubworld.isEmpty() || mySubworld.isEmpty()
                || mySubworld.equals(beaconSubworld);
    }

    // ── Recent-ping query ─────────────────────────────────────────────────────

    /**
     * Returns {@code true} if any player has sent a ping whose subworld matches
     * the given subworld within the last {@value #RECENT_PING_LIFETIME_MS} ms.
     *
     * <p>Expired entries are pruned lazily on each call, so no background task
     * is required to keep the map clean.</p>
     *
     * @param subworld the adventure subworld key to test (e.g. {@code "adventure3"})
     */
    public static boolean hasRecentPingInSubworld(String subworld) {
        if (subworld == null || subworld.isEmpty()) return false;
        long now = System.currentTimeMillis();
        recentPings.entrySet().removeIf(e -> now - e.getValue().timestamp() > RECENT_PING_LIFETIME_MS);
        for (BeaconData b : recentPings.values()) {
            if (subworld.equalsIgnoreCase(b.subworld())) return true;
        }
        return false;
    }

    // ── Connection lifecycle ──────────────────────────────────────────────────

    public static void connect(String mcServer, String world) {
        if (socket != null || !connecting.compareAndSet(false, true)) {
            System.out.println("[CosmicTeams] Connection already active or in progress.");
            return;
        }
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(SERVER_URL), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override public void onOpen(WebSocket ws) {
                        socket = ws;
                        disconnectedAtMs = -1L;
                        lastReconnectAttemptMs = 0L;
                        ws.request(1);
                    }

                    /**
                     * Handles incoming text frames.
                     *
                     * handleMessage is wrapped in try/catch/finally to guarantee that
                     * ws.request(1) is always called even if an unexpected exception
                     * escapes the handler. Without this guard, an unchecked exception
                     * would silently stall the WebSocket receive pipeline — no further
                     * messages would be dispatched until the socket was closed and
                     * reopened, with no error logged.
                     */
                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) {
                            try {
                                handleMessage(ws, buf.toString(), mcServer, world);
                            } catch (Exception e) {
                                System.err.println("[CosmicTeams] Unhandled error in message handler: " + e.getMessage());
                            } finally {
                                buf.setLength(0);
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    /**
                     * Handles server-sent WebSocket ping frames.
                     *
                     * The Java WebSocket API automatically sends a pong response to every
                     * ping frame — no manual pong handling is required. We only need to
                     * call ws.request(1) here so that the ping frame itself does not
                     * consume the one outstanding receive credit and stall the pipeline.
                     */
                    @Override
                    public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer message) {
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        boolean wasConnected = connected;
                        connected = false; connecting.set(false); socket = null;
                        System.err.println("[CosmicTeams] Connection error: " + error.getMessage());
                        if (wasConnected && disconnectedAtMs < 0) {
                            disconnectedAtMs = System.currentTimeMillis();
                            sendConnectionMsg("§e[CosmicTeams] Lost connection to relay server. Attempting to reconnect...");
                        }
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        boolean wasConnected = connected;
                        connected = false; connecting.set(false); socket = null;
                        System.out.println("[CosmicTeams] Disconnected from relay.");
                        if (wasConnected && disconnectedAtMs < 0) {
                            disconnectedAtMs = System.currentTimeMillis();
                            sendConnectionMsg("§e[CosmicTeams] Lost connection to relay server. Attempting to reconnect...");
                        }
                        return null;
                    }
                })
                .exceptionally(e -> {
                    connecting.set(false); socket = null;
                    System.err.println("[CosmicTeams] Failed to connect: " + e.getMessage());
                    if (disconnectedAtMs >= 0) {
                        sendConnectionMsg("§c[CosmicTeams] Could not reach relay server. Will retry in 30s.");
                    }
                    return null;
                });
    }

    // ── Inbound message handler ───────────────────────────────────────────────

    private static void handleMessage(WebSocket ws, String json,
                                      String mcServer, String world) {

        if (json.contains("\"type\":\"challenge\"")) {
            String nonce = extractStr(json, "nonce");
            performMojangAuth(nonce, success -> {
                if (!success) {
                    connecting.set(false);
                    sendChatMsg("§c[CosmicTeams] Mojang authentication failed. Are you in offline mode?");
                    return;
                }
                MinecraftClient c = MinecraftClient.getInstance();
                if (c.player == null) { connecting.set(false); return; }
                ws.sendText(String.format(
                        "{\"type\":\"auth\",\"username\":\"%s\",\"nonce\":\"%s\"," +
                                "\"server\":\"%s\",\"world\":\"%s\"}",
                        esc(c.player.getName().getString()), esc(nonce),
                        esc(mcServer), esc(world)), true);
            });

        } else if (json.contains("\"type\":\"auth_ok\"")) {
            connected = true;
            connecting.set(false);
            String role = extractStr(json, "role");
            if (role.isEmpty()) role = json.contains("\"isOwner\":true") ? "owner" : "member";
            String serverToken    = extractStr(json, "token");
            String serverTeamname = extractStr(json, "teamname");
            if (!serverToken.isEmpty() && !serverTeamname.isEmpty()) {
                CosmicTeamsConfig.get().setTeamData(serverToken, serverTeamname);
                CosmicTeamsConfig.get().setRole(role);
            } else {
                if (CosmicTeamsConfig.get().hasTeam()) CosmicTeamsConfig.get().clearTeamData();
            }
            sendChatMsg("§b[CosmicTeams] Connected to relay" +
                    (CosmicTeamsConfig.get().hasTeam()
                            ? " as member of §f\"" + CosmicTeamsConfig.get().getTeamName() + "\"§b."
                            : ". Use §f/team create <name>§b to create a team."));
            if (CosmicTeamsConfig.get().hasTeam()) refreshTeamList();

        } else if (json.contains("\"type\":\"auth_failed\"")) {
            connected = false; connecting.set(false);
            sendChatMsg("§c[CosmicTeams] Auth failed: " + extractStr(json, "reason"));

        } else if (json.contains("\"type\":\"session_replaced\"")) {
            connected = false; connecting.set(false);
            sendChatMsg("§e[CosmicTeams] Your session was replaced by another instance.");

        } else if (json.contains("\"type\":\"team_created\"")) {
            String token    = extractStr(json, "token");
            String teamname = extractStr(json, "teamname");
            CosmicTeamsConfig.get().setTeamData(token, teamname);
            CosmicTeamsConfig.get().setRole("owner");
            sendChatMsg("§a[CosmicTeams] Team §f\"" + teamname + "\"§a created! " +
                    "Invite players with §f/team invite <username>");
            refreshTeamList();

        } else if (json.contains("\"type\":\"team_joined\"")) {
            String token    = extractStr(json, "token");
            String teamname = extractStr(json, "teamname");
            CosmicTeamsConfig.get().setTeamData(token, teamname);
            CosmicTeamsConfig.get().setRole("member");
            sendChatMsg("§a[CosmicTeams] Joined team §f\"" + teamname + "\"§a!");
            refreshTeamList();

        } else if (json.contains("\"type\":\"member_joined\"")) {
            String u = extractStr(json, "username");
            if (!u.isEmpty()) {
                teammateNames.add(u);
                sendChatMsg("§a[CosmicTeams] §f" + u + "§a has joined the team!");
            }

        } else if (json.contains("\"type\":\"member_left\"")) {
            String u = extractStr(json, "username");
            if (!u.isEmpty()) {
                teammateNames.remove(u);
                teammateSubworlds.remove(u);
                recentPings.remove(u);
            }

        } else if (json.contains("\"type\":\"beacon\"")) {
            try {
                String player         = extractStr(json, "player");
                double x              = Double.parseDouble(extractNum(json, "x"));
                double y              = Double.parseDouble(extractNum(json, "y"));
                double z              = Double.parseDouble(extractNum(json, "z"));
                String beaconWorld    = extractStr(json, "world");
                String beaconSubworld = extractStr(json, "subworld");
                long   timestamp      = System.currentTimeMillis();

                recentPings.put(player, new BeaconData(
                        player, x, y, z, beaconWorld, beaconSubworld, timestamp, 0));

                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    activeBeacons.removeIf(b -> b.player().equals(player));
                    activeBeacons.add(new BeaconData(
                            player, x, y, z, beaconWorld, beaconSubworld, timestamp, 0));
                    if (mc.player != null) {
                        mc.player.sendMessage(Text.literal(buildReceivedPingMessage(
                                player, x, y, z, beaconWorld, beaconSubworld)), false);
                    }
                    CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
                    if (!cfg.isMuted(beaconWorld)) {
                        mc.getSoundManager().play(pingSound());
                    }
                });
            } catch (Exception e) {
                System.err.println("[CosmicTeams] Beacon parse error: " + e.getMessage());
            }

        } else if (json.contains("\"type\":\"death_beacon\"")) {
            try {
                String player         = extractStr(json, "player");
                double x              = Double.parseDouble(extractNum(json, "x"));
                double y              = Double.parseDouble(extractNum(json, "y"));
                double z              = Double.parseDouble(extractNum(json, "z"));
                String beaconWorld    = extractStr(json, "world");
                String beaconSubworld = extractStr(json, "subworld");
                long   timestamp      = System.currentTimeMillis();

                MinecraftClient mc = MinecraftClient.getInstance();
                mc.execute(() -> {
                    // Append without deduplication — each death stays as its own beacon.
                    activeDeathBeacons.add(new BeaconData(
                            player, x, y, z, beaconWorld, beaconSubworld, timestamp, 0));

                    if (mc.player != null) {
                        CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
                        String dimShort     = beaconWorld.contains(":")
                                ? beaconWorld.split(":")[1] : beaconWorld;
                        String worldDisplay = WorldNames.displayName(dimShort, beaconSubworld);
                        String subPart      = beaconSubworld.isEmpty()
                                ? "" : " §b(" + beaconSubworld + ")§r";
                        String chatMsg = cfg.compactPingMessages
                                ? "§f" + player + " §cdied."
                                : String.format("§f%s §cdied in §f%s%s §cat §f%d, %d, %d",
                                player, worldDisplay, subPart,
                                Math.round(x), Math.round(y), Math.round(z));
                        mc.player.sendMessage(Text.literal(chatMsg), false);

                        // Audio: mute list always applies.  World-gate is an additional
                        // filter — suppressed unless same world, unless global is toggled on.
                        String mySubworld   = getSubworld();
                        boolean inSameWorld = worldMatches(beaconWorld, beaconSubworld, mySubworld);
                        if (!cfg.isMuted(beaconWorld)
                                && (cfg.deathPingAudioGlobal || inSameWorld)) {
                            mc.getSoundManager().play(deathPingSound());
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("[CosmicTeams] Death beacon parse error: " + e.getMessage());
            }

        } else if (json.contains("\"type\":\"invite_notification\"")) {
            String  from          = extractStr(json, "from");
            String  teamname      = extractStr(json, "teamname");
            boolean alreadyOnTeam = json.contains("\"alreadyOnTeam\":true");
            if (alreadyOnTeam) {
                sendChatMsg("§b[CosmicTeams] §f" + from + "§b invited you to join §f\"" + teamname +
                        "\"§b. You must §f/team leave§b your current team before you can accept.");
            } else {
                sendChatMsg("§b[CosmicTeams] §f" + from + "§b invited you to §f\"" + teamname +
                        "\"§b. Run §f/team join " + teamname + "§b within 5 minutes.");
            }

        } else if (json.contains("\"type\":\"invite_cancelled\"")) {
            sendChatMsg("§e[CosmicTeams] A pending team invite you received has been cancelled.");

        } else if (json.contains("\"type\":\"role_changed\"")) {
            String newRole = extractStr(json, "role");
            String by      = extractStr(json, "by");
            CosmicTeamsConfig.get().setRole(newRole);
            sendChatMsg("moderator".equals(newRole)
                    ? "§a[CosmicTeams] You were promoted to §b[Mod]§a by §f" + by + "§a."
                    : "§e[CosmicTeams] You were demoted to §7[Member]§e by §f" + by + "§e.");

        } else if (json.contains("\"type\":\"kicked\"")) {
            String by = extractStr(json, "by");
            CosmicTeamsConfig.get().clearTeamData();
            activeBeacons.clear();
            activeDeathBeacons.clear();
            recentPings.clear();
            teammateNames.clear();
            teammateSubworlds.clear();
            sendChatMsg("§c[CosmicTeams] You were kicked from your team by §f" + by + "§c.");
            MinecraftClient.getInstance().execute(RelayClient::reconnectWithoutTeam);

        } else if (json.contains("\"type\":\"team_disbanded\"")) {
            String by = extractStr(json, "by");
            CosmicTeamsConfig.get().clearTeamData();
            activeBeacons.clear();
            activeDeathBeacons.clear();
            recentPings.clear();
            teammateNames.clear();
            teammateSubworlds.clear();
            sendChatMsg("§c[CosmicTeams] Your team was disbanded by §f" + by + "§c.");
            MinecraftClient.getInstance().execute(RelayClient::reconnectWithoutTeam);

        } else if (json.contains("\"type\":\"ownership_transferred\"")) {
            CosmicTeamsConfig.get().setRole("owner");
            sendChatMsg("§6[CosmicTeams] You are now the §6[Owner]§6 of the team.");

        } else if (json.contains("\"type\":\"team_renamed\"")) {
            String newname = extractStr(json, "newname");
            String by      = extractStr(json, "by");
            if (!newname.isEmpty()) {
                CosmicTeamsConfig cfg = CosmicTeamsConfig.get();
                String existingToken = cfg.getToken();
                if (existingToken != null && !existingToken.isEmpty()) {
                    cfg.setTeamData(existingToken, newname);
                }
                sendChatMsg("§b[CosmicTeams] Team renamed to §f\"" + newname + "\"§b by §f" + by + "§b.");
            }

        } else if (json.contains("\"type\":\"error\"")) {
            sendChatMsg("§c[CosmicTeams] " + extractStr(json, "message"));

        } else if (json.contains("\"type\":\"inv_request\"")) {
            String requester = extractStr(json, "requester");
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player == null || socket == null) return;
                String slotsJson = serializeInventory(mc);
                socket.sendText("{\"type\":\"inv_data\",\"slots\":" + slotsJson + "}", true);
                System.out.println("[CosmicTeams] Sent inventory snapshot to " + requester);
            });

        } else if (json.contains("\"type\":\"inv_data\"")) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                if (mc.player == null) return;
                try {
                    String player = extractStr(json, "player");
                    com.google.gson.JsonArray slotsArr = com.google.gson.JsonParser
                            .parseString(json).getAsJsonObject().getAsJsonArray("slots");
                    RegistryWrapper.WrapperLookup registries = mc.player.getRegistryManager();
                    List<ItemStack> stacks = new ArrayList<>(41);
                    for (com.google.gson.JsonElement elem : slotsArr) {
                        String snbt = (elem.isJsonNull()) ? "" : elem.getAsString();
                        if (snbt.isEmpty()) {
                            stacks.add(ItemStack.EMPTY);
                        } else {
                            try {
                                net.minecraft.nbt.NbtCompound nbt =
                                        net.minecraft.nbt.StringNbtReader.readCompound(snbt);
                                stacks.add(ItemStack.CODEC
                                        .parse(registries.getOps(NbtOps.INSTANCE), nbt)
                                        .result().orElse(ItemStack.EMPTY));
                            } catch (Exception e) {
                                stacks.add(ItemStack.EMPTY);
                            }
                        }
                    }
                    while (stacks.size() < 41) stacks.add(ItemStack.EMPTY);
                    mc.setScreen(new InvSeeScreen(player, stacks));
                } catch (Exception e) {
                    System.err.println("[CosmicTeams] inv_data parse error: " + e.getMessage());
                    sendChatMsg("§c[CosmicTeams] Failed to read inventory data.");
                }
            });

        } else if (json.contains("\"type\":\"inv_error\"")) {
            sendChatMsg("§c[CosmicTeams] " + extractStr(json, "message"));
        }
    }

    // ── Inventory serialization ───────────────────────────────────────────────

    static String serializeInventory(MinecraftClient mc) {
        if (mc.player == null) return "[]";
        RegistryWrapper.WrapperLookup registries = mc.player.getRegistryManager();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 41; i++) {
            if (i > 0) sb.append(',');
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) {
                sb.append("\"\"");
            } else {
                try {
                    net.minecraft.nbt.NbtElement nbt = ItemStack.CODEC
                            .encodeStart(registries.getOps(NbtOps.INSTANCE), stack)
                            .result().orElse(null);
                    if (nbt != null) {
                        String snbt = nbt.toString()
                                .replace("\\", "\\\\")
                                .replace("\"", "\\\"");
                        sb.append('"').append(snbt).append('"');
                    } else {
                        sb.append("\"\"");
                    }
                } catch (Exception e) {
                    sb.append("\"\"");
                }
            }
        }
        sb.append(']');
        return sb.toString();
    }

    // ── Mojang auth ───────────────────────────────────────────────────────────

    private static void performMojangAuth(String nonce, Consumer<Boolean> callback) {
        MinecraftClient client = MinecraftClient.getInstance();
        Session         session = client.getSession();
        String          token  = session.getAccessToken();
        String          uuid   = session.getUuidOrNull() != null
                ? session.getUuidOrNull().toString().replace("-", "") : "";
        if (token == null || token.isEmpty() || token.equals("FabricMC")) {
            callback.accept(false); return;
        }
        HttpClient.newHttpClient()
                .sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/join"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(String.format(
                                        "{\"accessToken\":\"%s\",\"selectedProfile\":\"%s\"," +
                                                "\"serverId\":\"%s\"}", token, uuid, nonce)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> callback.accept(r.statusCode() == 204))
                .exceptionally(e -> { callback.accept(false); return null; });
    }

    // ── Outbound helpers ──────────────────────────────────────────────────────

    /**
     * Sends a location ping to the relay and registers the beacon locally so
     * the sender also sees their own beacon immediately.
     */
    public static void sendLocationPing(double x, double y, double z, String world) {
        if (!connected || socket == null) return;
        String subworld = getSubworld();
        socket.sendText(String.format(
                "{\"type\":\"ping\",\"x\":%.4f,\"y\":%.4f,\"z\":%.4f," +
                        "\"world\":\"%s\",\"subworld\":\"%s\"}",
                x, y, z, esc(world), esc(subworld)), true);

        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getName().getString() : "";
        long   timestamp  = System.currentTimeMillis();
        activeBeacons.removeIf(b -> b.player().equals(playerName));
        BeaconData sent = new BeaconData(playerName, x, y, z, world, subworld, timestamp, 0);
        activeBeacons.add(sent);
        recentPings.put(playerName, sent);
        CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
        if (!cfg.isMuted(world)) {
            mc.execute(() -> mc.getSoundManager().play(pingSound()));
        }
    }

    /**
     * Broadcasts a death ping at the given coordinates to all online teammates,
     * registers the beacon locally, and displays a death location message in
     * the dying player's own chat.
     *
     * <p>Unlike regular pings, the local beacon is appended without removing
     * existing death beacons for this player, so multiple deaths within the
     * storage window all remain visible simultaneously.</p>
     *
     * <p>No audio alert is played for the sender — they are obviously aware of
     * their own death.  A chat message is shown instead so the player has a
     * persistent record of their death coordinates to refer back to.</p>
     */
    public static void sendDeathPing(double x, double y, double z, String world) {
        if (!connected || socket == null) return;
        String subworld = getSubworld();
        socket.sendText(String.format(
                "{\"type\":\"death_ping\",\"x\":%.4f,\"y\":%.4f,\"z\":%.4f," +
                        "\"world\":\"%s\",\"subworld\":\"%s\"}",
                x, y, z, esc(world), esc(subworld)), true);

        MinecraftClient mc = MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getName().getString() : "";
        long   timestamp  = System.currentTimeMillis();

        // Append — do NOT remove existing death beacons for this player.
        activeDeathBeacons.add(new BeaconData(playerName, x, y, z, world, subworld, timestamp, 0));

        // Notify the dying player of their death coordinates in chat.
        // This gives them a persistent record to refer back to even after the
        // death screen is dismissed or a world transfer occurs.
        CosmicTeamsConfig.Settings cfg = CosmicTeamsConfig.get().settings;
        String dimShort = world.contains(":") ? world.split(":")[1] : world;
        String chatMsg;
        if (cfg.compactPingMessages) {
            chatMsg = String.format("§c☠ You died at §f%d, %d, %d",
                    Math.round(x), Math.round(y), Math.round(z));
        } else {
            String worldDisplay = WorldNames.displayName(dimShort, subworld);
            String subPart      = subworld.isEmpty() ? "" : " §b(" + subworld + ")§r";
            chatMsg = String.format("§c☠ You died in §f%s%s §cat §f%d, %d, %d",
                    worldDisplay, subPart,
                    Math.round(x), Math.round(y), Math.round(z));
        }
        final String finalMsg = chatMsg;
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendMessage(Text.literal(finalMsg), false);
        });
    }

    /** Notifies the relay that this player's world or subworld has changed. */
    public static void sendLocationUpdate(String world, String subworld) {
        if (!connected || socket == null) return;
        socket.sendText(String.format(
                "{\"type\":\"location_update\",\"world\":\"%s\",\"subworld\":\"%s\"}",
                esc(world), esc(subworld)), true);
    }

    /**
     * Sends an inventory-view request for the given teammate to the relay.
     */
    public static void sendInvSee(String target) {
        if (!connected || socket == null) return;
        socket.sendText(String.format(
                "{\"type\":\"inv_request\",\"target\":\"%s\"}", esc(target)), true);
    }

    /** Asynchronously fetches the current subworld of every online teammate. */
    public static void fetchTeamLocations() {
        String token = CosmicTeamsConfig.get().getToken();
        if (token == null || token.isEmpty()) return;
        String encoded = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create(httpBase() + "/teamlocations?token=" + encoded))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> {
                    if (r.statusCode() != 200) return;
                    teammateSubworlds.clear();
                    teammateWorlds.clear();
                    String json = r.body();
                    int idx = 0;
                    while ((idx = json.indexOf("\"username\":\"", idx)) >= 0) {
                        idx += 12;
                        int uEnd = json.indexOf('"', idx);
                        if (uEnd < 0) break;
                        String username = json.substring(idx, uEnd);
                        idx = uEnd + 1;
                        int objEnd = json.indexOf('}', idx);
                        if (objEnd < 0) objEnd = json.length();
                        String obj = json.substring(idx, objEnd);
                        int swStart = obj.indexOf("\"subworld\":\"");
                        if (swStart >= 0) {
                            swStart += 12;
                            int swEnd = obj.indexOf('"', swStart);
                            teammateSubworlds.put(username, swEnd >= 0 ? obj.substring(swStart, swEnd) : "");
                        } else {
                            teammateSubworlds.put(username, "");
                        }
                        int wStart = obj.indexOf("\"world\":\"");
                        if (wStart >= 0) {
                            wStart += 9;
                            int wEnd = obj.indexOf('"', wStart);
                            teammateWorlds.put(username, wEnd >= 0 ? obj.substring(wStart, wEnd) : "");
                        } else {
                            teammateWorlds.put(username, "");
                        }
                        idx = objEnd + 1;
                    }
                })
                .exceptionally(e -> {
                    System.err.println("[CosmicTeams] fetchTeamLocations failed: " + e.getMessage());
                    return null;
                });
    }

    public static void sendRaw(String json) { if (socket != null) socket.sendText(json, true); }

    // ── Extraction epoch reporting ─────────────────────────────────────────────

    public static void sendExtractionEpoch(long epochMs) {
        String token = CosmicTeamsConfig.get().getToken();
        if (token == null || token.isEmpty()) return;
        String body = "{\"token\":\"" + esc(token) + "\",\"epoch\":" + epochMs + "}";
        HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create(httpBase() + "/extraction_epoch"))
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .header("Content-Type", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .exceptionally(e -> {
                    System.err.println("[CosmicTeams] sendExtractionEpoch failed: " + e.getMessage());
                    return null;
                });
    }

    public static void fetchExtractionEpochs() {
        String token = CosmicTeamsConfig.get().getToken();
        if (token == null || token.isEmpty()) return;
        String encoded = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create(httpBase() + "/extraction_epochs?token=" + encoded))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> {
                    if (r.statusCode() != 200) return;
                    extractionEpochs.clear();
                    String json  = r.body();
                    int    start = json.indexOf('[');
                    int    end   = json.lastIndexOf(']');
                    if (start < 0 || end < 0 || end <= start) return;
                    for (String part : json.substring(start + 1, end).split(",")) {
                        part = part.trim();
                        if (part.isEmpty()) continue;
                        try { extractionEpochs.add(Long.parseLong(part)); }
                        catch (NumberFormatException ignored) {}
                    }
                })
                .exceptionally(e -> {
                    System.err.println("[CosmicTeams] fetchExtractionEpochs failed: " + e.getMessage());
                    return null;
                });
    }

    public static void disconnect() {
        if (socket != null && connected) socket.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect");
        socket = null; connected = false; connecting.set(false);
        disconnectedAtMs = -1L;
        lastReconnectAttemptMs = 0L;
        teammateNames.clear();
        teammateSubworlds.clear();
        teammateWorlds.clear();
        extractionEpochs.clear();
        recentPings.clear();
        activeDeathBeacons.clear();
    }

    public static boolean tickReconnect(String mcServer, String world) {
        if (connected || connecting.get() || disconnectedAtMs < 0) return false;
        long now    = System.currentTimeMillis();
        long ref    = (lastReconnectAttemptMs == 0) ? disconnectedAtMs : lastReconnectAttemptMs;
        long waitMs = (lastReconnectAttemptMs == 0) ? INITIAL_RECONNECT_MS : RECONNECT_INTERVAL_MS;
        if (now - ref < waitMs) return false;
        lastReconnectAttemptMs = now;
        connect(mcServer, world);
        return true;
    }

    public static boolean isConnected() { return connected; }

    public static void reconnectWithoutTeam() {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.world == null || c.player == null) return;
        String srv = c.getCurrentServerEntry() != null
                ? c.getCurrentServerEntry().address : "singleplayer";
        disconnect();
        connect(srv, c.world.getRegistryKey().getValue().toString());
    }

    // ── Sound ─────────────────────────────────────────────────────────────────

    private static PositionedSoundInstance pingSound() {
        float volume = CosmicTeamsConfig.get().settings.pingVolume;
        return PositionedSoundInstance.ui(ModSounds.PING_ALERT, 1.0f, volume);
    }

    /**
     * Plays the death-ping alert using the vanilla pillager hurt sound.
     * Volume is shared with {@link CosmicTeamsConfig.Settings#pingVolume}.
     */
    private static PositionedSoundInstance deathPingSound() {
        float volume = CosmicTeamsConfig.get().settings.pingVolume;
        return PositionedSoundInstance.ui(net.minecraft.sound.SoundEvents.ENTITY_PILLAGER_HURT,
                1.0f, volume);
    }

    // ── Chat message helpers ──────────────────────────────────────────────────

    private static void sendChatMsg(String message) {
        MinecraftClient.getInstance().execute(() -> {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c.player != null) c.player.sendMessage(Text.literal(message), false);
        });
    }

    private static void sendConnectionMsg(String message) {
        if (!CosmicTeamsConfig.get().settings.showConnectionMessages) return;
        sendChatMsg(message);
    }

    private static String buildReceivedPingMessage(String player,
                                                   double x, double y, double z,
                                                   String beaconWorld, String beaconSubworld) {
        if (CosmicTeamsConfig.get().settings.compactPingMessages) {
            return "§f" + player + " §apinged.";
        }
        String dimShort     = beaconWorld.contains(":") ? beaconWorld.split(":")[1] : beaconWorld;
        String worldDisplay = WorldNames.displayName(dimShort, beaconSubworld);
        String subPart      = beaconSubworld.isEmpty() ? "" : " §b(" + beaconSubworld + ")§r";
        return String.format("§f%s §apinged in §f%s%s §aat §f%d, %d, %d",
                player, worldDisplay, subPart,
                Math.round(x), Math.round(y), Math.round(z));
    }

    // ── Team member list ──────────────────────────────────────────────────────

    private static void refreshTeamList() {
        String token = CosmicTeamsConfig.get().getToken();
        if (token == null || token.isEmpty()) return;
        String encoded = java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
        HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder()
                                .uri(URI.create(httpBase() + "/listteam?token=" + encoded))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> {
                    if (r.statusCode() != 200) return;
                    String json = r.body();
                    teammateNames.clear();
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.execute(() -> { if (mc.player != null) teammateNames.add(mc.player.getName().getString()); });
                    int idx = 0;
                    while ((idx = json.indexOf("\"username\":\"", idx)) >= 0) {
                        idx += 12;
                        int end = json.indexOf('"', idx);
                        if (end < 0) break;
                        teammateNames.add(json.substring(idx, end));
                        idx = end;
                    }
                })
                .exceptionally(e -> null);
    }

    // ── URL helpers ───────────────────────────────────────────────────────────

    private static String httpBase() {
        return SERVER_URL
                .replace("ws://",  "http://")
                .replace("wss://", "https://")
                .replaceAll(":8080$", ":8081");
    }

    // ── JSON mini-parser helpers ──────────────────────────────────────────────

    public static String extractStr(String json, String key) {
        String tag = "\"" + key + "\":\"";
        int s = json.indexOf(tag); if (s < 0) return "";
        s += tag.length();
        int e = json.indexOf('"', s);
        return e < 0 ? "" : json.substring(s, e);
    }

    static String extractNum(String json, String key) {
        String tag = "\"" + key + "\":";
        int s = json.indexOf(tag) + tag.length(), e = json.length();
        int c = json.indexOf(',', s); if (c > 0 && c < e) e = c;
        int b = json.indexOf('}', s); if (b > 0 && b < e) e = b;
        return json.substring(s, e).trim();
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\""); }
}