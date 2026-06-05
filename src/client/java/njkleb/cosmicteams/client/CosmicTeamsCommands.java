package njkleb.cosmicteams.client;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

public class CosmicTeamsCommands {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static long disbandConfirmTimestamp = 0;
    private static final long DISBAND_CONFIRM_WINDOW_MS = 10_000;

    static final String API_BASE = RelayClient.SERVER_URL
            .replace("ws://",  "http://")
            .replace("wss://", "https://")
            .replaceAll(":8080$", ":8081");

    // ── Suggestion-provider caches ────────────────────────────────────────────

    private static final long CACHE_TTL_MS = 10_000;

    // All users connected to the relay — for /team invite
    private static final CopyOnWriteArrayList<String> cachedRelayUsers = new CopyOnWriteArrayList<>();
    private static volatile long relayUsersCacheMs = 0;

    // All team names — for /team join
    private static final CopyOnWriteArrayList<String> cachedRelayTeams = new CopyOnWriteArrayList<>();
    private static volatile long relayTeamsCacheMs = 0;

    // ── Suggestion providers ──────────────────────────────────────────────────

    /**
     * All players currently connected to the relay server.
     * Merged with the current Minecraft tab-list for immediate results.
     * Used for: {@code /team invite}
     */
    private static final SuggestionProvider<FabricClientCommandSource> RELAY_USERS =
            (context, builder) -> {
                long now = System.currentTimeMillis();
                if (now - relayUsersCacheMs > CACHE_TTL_MS) {
                    relayUsersCacheMs = now;
                    get("/onlineusers", (status, json) -> {
                        if (status != 200) return;
                        cachedRelayUsers.clear();
                        parseStringArray(json, "users", cachedRelayUsers);
                    });
                }
                String remaining = builder.getRemaining().toLowerCase();
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().getPlayerList().forEach(e -> {
                        String name = e.getProfile().name();
                        if (name != null && name.toLowerCase().startsWith(remaining)
                                && !cachedRelayUsers.contains(name))
                            builder.suggest(name);
                    });
                }
                for (String name : cachedRelayUsers)
                    if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
                return builder.buildFuture();
            };

    /**
     * All team names currently on the relay server.
     * Used for: {@code /team join}
     */
    private static final SuggestionProvider<FabricClientCommandSource> RELAY_TEAMS =
            (context, builder) -> {
                long now = System.currentTimeMillis();
                if (now - relayTeamsCacheMs > CACHE_TTL_MS) {
                    relayTeamsCacheMs = now;
                    get("/allteams", (status, json) -> {
                        if (status != 200) return;
                        cachedRelayTeams.clear();
                        parseStringArray(json, "teams", cachedRelayTeams);
                    });
                }
                String remaining = builder.getRemaining().toLowerCase();
                for (String name : cachedRelayTeams)
                    if (name.toLowerCase().startsWith(remaining)) builder.suggest(name);
                return builder.buildFuture();
            };

    // All members of the local player's team (including offline) — for /team kick, promote, demote, owner
    private static final CopyOnWriteArrayList<String> cachedTeamMembers = new CopyOnWriteArrayList<>();
    private static volatile long teamMembersCacheMs = 0;

    /**
     * All members of the local player's team (including offline).
     * Used for: {@code /team kick}, {@code /team promote}, {@code /team demote},
     * {@code /team owner}
     */
    private static final SuggestionProvider<FabricClientCommandSource> RELAY_TEAM_MEMBERS =
            (context, builder) -> {
                long now = System.currentTimeMillis();
                if (now - teamMembersCacheMs > CACHE_TTL_MS) {
                    teamMembersCacheMs = now;
                    String token = CosmicTeamsConfig.get().getToken();
                    if (token != null) {
                        get("/teammembers?token=" + token, (status, json) -> {
                            if (status != 200) return;
                            cachedTeamMembers.clear();
                            parseStringArray(json, "members", cachedTeamMembers);
                        });
                    }
                }
                String remaining = builder.getRemaining().toLowerCase();
                MinecraftClient mc = MinecraftClient.getInstance();
                String self = mc.player != null ? mc.player.getName().getString() : "";
                for (String name : cachedTeamMembers)
                    if (!name.equalsIgnoreCase(self) && name.toLowerCase().startsWith(remaining))
                        builder.suggest(name);
                return builder.buildFuture();
            };

    /**
     * Online teammates only, sourced directly from {@link RelayClient#teammateNames}.
     * Used for: {@code /invsee}
     */
    private static final SuggestionProvider<FabricClientCommandSource> ONLINE_TEAMMATES =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase();
                MinecraftClient mc = MinecraftClient.getInstance();
                String self = mc.player != null ? mc.player.getName().getString() : "";
                for (String name : RelayClient.teammateNames)
                    if (!name.equalsIgnoreCase(self) && name.toLowerCase().startsWith(remaining))
                        builder.suggest(name);
                return builder.buildFuture();
            };

    // ── Command registration ──────────────────────────────────────────────────

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {

            // ── /team ─────────────────────────────────────────────────────────
            dispatcher.register(
                    ClientCommandManager.literal("team")

                            .executes(ctx -> { showHelp(); return 1; })

                            .then(ClientCommandManager.argument("unknown", StringArgumentType.greedyString())
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Unknown command. " +
                                                "Type §f/team§c for a list of commands.");
                                        return 1;
                                    }))

                            // /team create <name>
                            .then(ClientCommandManager.literal("create")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team create <teamname>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("teamname", StringArgumentType.word())
                                            .executes(ctx -> {
                                                createTeam(StringArgumentType.getString(ctx, "teamname"));
                                                return 1;
                                            })))

                            // /team invite <player>
                            .then(ClientCommandManager.literal("invite")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team invite <username>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                            .suggests(RELAY_USERS)
                                            .executes(ctx -> {
                                                invitePlayer(StringArgumentType.getString(ctx, "username"));
                                                return 1;
                                            })))

                            // /team join <name>
                            .then(ClientCommandManager.literal("join")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team join <teamname>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("teamname", StringArgumentType.word())
                                            .suggests(RELAY_TEAMS)
                                            .executes(ctx -> {
                                                joinTeam(StringArgumentType.getString(ctx, "teamname"));
                                                return 1;
                                            })))

                            // /team leave
                            .then(ClientCommandManager.literal("leave")
                                    .executes(ctx -> { leaveTeam(); return 1; }))

                            // /team list
                            .then(ClientCommandManager.literal("list")
                                    .executes(ctx -> { listTeam(); return 1; }))

                            // /team kick <player>
                            .then(ClientCommandManager.literal("kick")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team kick <username>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                            .suggests(RELAY_TEAM_MEMBERS)
                                            .executes(ctx -> {
                                                kickPlayer(StringArgumentType.getString(ctx, "username"));
                                                return 1;
                                            })))

                            // /team promote <player>
                            .then(ClientCommandManager.literal("promote")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team promote <username>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                            .suggests(RELAY_TEAM_MEMBERS)
                                            .executes(ctx -> {
                                                promotePlayer(StringArgumentType.getString(ctx, "username"));
                                                return 1;
                                            })))

                            // /team demote <player>
                            .then(ClientCommandManager.literal("demote")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team demote <username>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                            .suggests(RELAY_TEAM_MEMBERS)
                                            .executes(ctx -> {
                                                demotePlayer(StringArgumentType.getString(ctx, "username"));
                                                return 1;
                                            })))

                            // /team owner <player>
                            .then(ClientCommandManager.literal("owner")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team owner <username>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                            .suggests(RELAY_TEAM_MEMBERS)
                                            .executes(ctx -> {
                                                transferOwner(StringArgumentType.getString(ctx, "username"));
                                                return 1;
                                            })))

                            // /team rename <newname>
                            .then(ClientCommandManager.literal("rename")
                                    .executes(ctx -> {
                                        msg("§c[CosmicTeams] Usage: §f/team rename <newname>");
                                        return 1;
                                    })
                                    .then(ClientCommandManager.argument("newname", StringArgumentType.word())
                                            .executes(ctx -> {
                                                renameTeam(StringArgumentType.getString(ctx, "newname"));
                                                return 1;
                                            })))

                            // /team disband
                            .then(ClientCommandManager.literal("disband")
                                    .executes(ctx -> { disband(); return 1; }))

                            // /team status
                            .then(ClientCommandManager.literal("status")
                                    .executes(ctx -> { showStatus(); return 1; }))

                            // /team help
                            .then(ClientCommandManager.literal("help")
                                    .executes(ctx -> { showHelp(); return 1; }))
            );

            // ── /invsee <username> ────────────────────────────────────────────
            dispatcher.register(
                    ClientCommandManager.literal("invsee")
                            .executes(ctx -> {
                                msg("§c[CosmicTeams] Usage: §f/invsee <username>");
                                return 1;
                            })
                            .then(ClientCommandManager.argument("username", StringArgumentType.word())
                                    .suggests(ONLINE_TEAMMATES)
                                    .executes(ctx -> {
                                        invsee(StringArgumentType.getString(ctx, "username"));
                                        return 1;
                                    }))
            );
        });
    }

    // ── Command implementations ───────────────────────────────────────────────

    private static void showHelp() {
        msg("§b§l--- CosmicTeams Commands ---");
        msg("§f/team create <name>   §7— Create a new team");
        msg("§f/team invite <player> §7— Invite a player §b(moderator+)");
        msg("§f/team join <name>     §7— Accept a team invitation");
        msg("§f/team leave           §7— Leave your current team");
        msg("§f/team list            §7— List all members of your team");
        msg("§f/team kick <player>   §7— Kick a player §b(moderator+)");
        msg("§f/team promote <player>§7— Promote member → moderator §b(moderator+)");
        msg("§f/team demote <player> §7— Demote moderator → member §b(moderator+)");
        msg("§f/team owner <player>  §7— Transfer ownership §6(owner only)");
        msg("§f/team rename <name>   §7— Rename your team §6(owner only)");
        msg("§f/team disband         §7— Disband your team §6(owner only)");
        msg("§f/team status          §7— Show team and relay status");
        msg("§f/invsee <player>      §7— View an online teammate's inventory");
        msg("§7Tap §f[B]§7 to ping your location. Hold §f[B]§7 to aim a ping at a block.");
    }

    private static void createTeam(String teamname) {
        if (!RelayClient.isConnected()) { msg("§c[CosmicTeams] Not connected to relay server."); return; }
        if (CosmicTeamsConfig.get().hasTeam()) {
            msg("§c[CosmicTeams] You are already on a team. Use §f/team leave§c first.");
            return;
        }
        RelayClient.sendRaw(String.format(
                "{\"type\":\"createteam\",\"teamname\":\"%s\"}", esc(teamname)));
    }

    private static void joinTeam(String teamname) {
        if (!RelayClient.isConnected()) { msg("§c[CosmicTeams] Not connected to relay server."); return; }
        if (CosmicTeamsConfig.get().hasTeam()) {
            msg("§c[CosmicTeams] You are already on a team. Use §f/team leave§c first.");
            return;
        }
        RelayClient.sendRaw(String.format(
                "{\"type\":\"jointeam\",\"teamname\":\"%s\"}", esc(teamname)));
    }

    private static void invitePlayer(String invitee) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())    { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isModerator()) {
            msg("§c[CosmicTeams] Only moderators and the team owner can invite players.");
            return;
        }
        String body = fmt("{\"token\":\"%s\",\"invitee\":\"%s\"}", config.getToken(), invitee);
        post("/invite", body, (status, json) -> {
            if (status == 200) {
                if (json.contains("\"cancelled\":true"))
                    msg("§e[CosmicTeams] Your pending invite to §f" + invitee + "§e has been cancelled.");
                else
                    msg("§a[CosmicTeams] Invited §f" + invitee + "§a to the team. Expires in 5 minutes.");
            } else if (status == 403) {
                msg("§c[CosmicTeams] Only moderators and the team owner can invite players.");
            } else if (status == 409) {
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            } else if (status == 401) {
                msg("§c[CosmicTeams] Authentication error.");
            } else {
                msg("§c[CosmicTeams] Failed to send invite (HTTP " + status + ").");
            }
        });
    }

    private static void leaveTeam() {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam()) { msg("§c[CosmicTeams] You are not on a team."); return; }
        String body = fmt("{\"token\":\"%s\"}", config.getToken());
        post("/leaveteam", body, (status, json) -> {
            if (status == 200) {
                String teamName = config.getTeamName();
                config.clearTeamData();
                RelayClient.activeBeacons.clear();
                RelayClient.reconnectWithoutTeam();
                msg("§a[CosmicTeams] You have left team §f\"" + teamName + "\"§a.");
            } else if (status == 403) {
                msg("§c[CosmicTeams] You are the team owner and cannot leave. " +
                        "Transfer ownership with §f/team owner <username>§c " +
                        "or disband with §f/team disband§c.");
            } else {
                msg("§c[CosmicTeams] Failed to leave team (HTTP " + status + ").");
            }
        });
    }

    private static void listTeam() {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam()) { msg("§c[CosmicTeams] You are not on a team."); return; }
        String encoded = URLEncoder.encode(config.getToken(), StandardCharsets.UTF_8);
        get("/listteam?token=" + encoded, (status, json) -> {
            if (status != 200) { msg("§c[CosmicTeams] Failed to fetch team list."); return; }
            String teamname = RelayClient.extractStr(json, "teamname");
            msg("§b§l--- Team: §f" + teamname + " §b§l---");
            int idx = 0;
            while ((idx = json.indexOf("\"username\":\"", idx)) >= 0) {
                idx += 12;
                int end = json.indexOf('"', idx);
                if (end < 0) break;
                String username = json.substring(idx, end);
                int objEnd = json.indexOf('}', end);
                String obj = objEnd >= 0 ? json.substring(end, objEnd) : "";
                String  role     = extractFromObj(obj, "role");
                boolean online   = obj.contains("\"online\":true");
                String  world    = extractFromObj(obj, "world");
                String  subworld = extractFromObj(obj, "subworld");

                // ● color reflects rank
                String bulletColor;
                if ("owner".equals(role) || obj.contains("\"isOwner\":true")) {
                    bulletColor = "§6"; // gold  — owner
                } else if ("moderator".equals(role)) {
                    bulletColor = "§b"; // aqua  — moderator
                } else {
                    bulletColor = "§f"; // white — member
                }

                // Username color reflects online status
                String nameColor = online ? "§a" : "§c";

                // Location suffix — only shown when online
                String locationPart = "";
                if (online && !world.isEmpty()) {
                    String dimShort     = world.contains(":") ? world.split(":")[1] : world;
                    String worldDisplay = WorldNames.displayName(dimShort, subworld);
                    locationPart = subworld.isEmpty()
                            ? " §f- §r" + worldDisplay
                            : " §f- §r" + worldDisplay + " §7(§b" + subworld + "§7)";
                }

                msg(bulletColor + "● §r" + nameColor + username + "§r" + locationPart);
                idx = end;
            }
        });
    }

    private static void kickPlayer(String target) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())    { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isModerator()) {
            msg("§c[CosmicTeams] Only moderators and the team owner can kick players.");
            return;
        }
        String body = fmt("{\"token\":\"%s\",\"target\":\"%s\"}", config.getToken(), target);
        post("/kick", body, (status, json) -> {
            if (status == 200)
                msg("§a[CosmicTeams] Kicked §f" + target + "§a from the team.");
            else if (status == 403)
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            else if (status == 404)
                msg("§c[CosmicTeams] §f" + target + "§c is not on your team.");
            else
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
        });
    }

    private static void promotePlayer(String target) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())    { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isModerator()) {
            msg("§c[CosmicTeams] Only moderators and the team owner can promote players.");
            return;
        }
        String body = fmt("{\"token\":\"%s\",\"target\":\"%s\"}", config.getToken(), target);
        post("/promote", body, (status, json) -> {
            if (status == 200)
                msg("§a[CosmicTeams] §f" + target + " §ahas been promoted to §bModerator§a.");
            else if (status == 403)
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            else if (status == 404)
                msg("§c[CosmicTeams] §f" + target + "§c is not on your team.");
            else if (status == 409)
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            else
                msg("§c[CosmicTeams] Failed to promote player (HTTP " + status + ").");
        });
    }

    private static void demotePlayer(String target) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())    { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isModerator()) {
            msg("§c[CosmicTeams] Only moderators and the team owner can demote players.");
            return;
        }
        String body = fmt("{\"token\":\"%s\",\"target\":\"%s\"}", config.getToken(), target);
        post("/demote", body, (status, json) -> {
            if (status == 200)
                msg("§a[CosmicTeams] §f" + target + " §ahas been demoted to §7Member§a.");
            else if (status == 403)
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            else if (status == 404)
                msg("§c[CosmicTeams] §f" + target + "§c is not on your team.");
            else if (status == 409)
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            else
                msg("§c[CosmicTeams] Failed to demote player (HTTP " + status + ").");
        });
    }

    private static void transferOwner(String target) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())  { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isOwner()) { msg("§c[CosmicTeams] Only the team owner can transfer ownership."); return; }
        String body = fmt("{\"token\":\"%s\",\"target\":\"%s\"}", config.getToken(), target);
        post("/transferowner", body, (status, json) -> {
            if (status == 200) {
                config.setRole("member");
                msg("§a[CosmicTeams] Ownership transferred to §f" + target +
                        "§a. You are now a regular member.");
            } else if (status == 403) {
                msg("§c[CosmicTeams] Only the team owner can transfer ownership.");
            } else if (status == 404) {
                msg("§c[CosmicTeams] §f" + target + "§c is not on your team.");
            } else if (status == 409) {
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            } else {
                msg("§c[CosmicTeams] Failed to transfer ownership (HTTP " + status + ").");
            }
        });
    }

    private static void renameTeam(String newname) {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())  { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isOwner()) { msg("§c[CosmicTeams] Only the team owner can rename the team."); return; }
        String body = fmt("{\"token\":\"%s\",\"newname\":\"%s\"}", config.getToken(), newname);
        post("/renameteam", body, (status, json) -> {
            if (status == 200) {
                // The server also broadcasts team_renamed to all members (including us),
                // so RelayClient.handleMessage will update config.teamName automatically.
                // We echo a local confirmation here in case the WS message races.
                String resolved = RelayClient.extractStr(json, "newname");
                String effective = resolved.isEmpty() ? newname : resolved;
                config.setTeamData(config.getToken(), effective);
                msg("§a[CosmicTeams] Team renamed to §f\"" + effective + "\"§a.");
            } else if (status == 403) {
                msg("§c[CosmicTeams] Only the team owner can rename the team.");
            } else if (status == 409) {
                msg("§c[CosmicTeams] " + RelayClient.extractStr(json, "error"));
            } else {
                msg("§c[CosmicTeams] Failed to rename team (HTTP " + status + ").");
            }
        });
    }

    private static void disband() {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam())  { msg("§c[CosmicTeams] You are not on a team."); return; }
        if (!config.isOwner()) { msg("§c[CosmicTeams] Only the team owner can disband the team."); return; }
        long now = System.currentTimeMillis();
        if (disbandConfirmTimestamp == 0 || now - disbandConfirmTimestamp > DISBAND_CONFIRM_WINDOW_MS) {
            disbandConfirmTimestamp = now;
            msg("§c§l[CosmicTeams] Warning: §r§cThis will permanently disband team §f\""
                    + config.getTeamName() + "\"§c and remove all members.");
            msg("§cRun §f/team disband§c again within 10 seconds to confirm.");
            return;
        }
        disbandConfirmTimestamp = 0;
        String body = fmt("{\"token\":\"%s\"}", config.getToken());
        post("/disband", body, (status, json) -> {
            if (status == 200) {
                String teamName = config.getTeamName();
                config.clearTeamData();
                RelayClient.activeBeacons.clear();
                RelayClient.reconnectWithoutTeam();
                msg("§a[CosmicTeams] Team §f\"" + teamName + "\"§a has been disbanded.");
            } else if (status == 403) {
                msg("§c[CosmicTeams] Only the team owner can disband the team.");
            } else {
                msg("§c[CosmicTeams] Failed to disband team (HTTP " + status + ").");
            }
        });
    }

    private static void showStatus() {
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (config.hasTeam()) {
            String roleLabel = switch (config.getRole()) {
                case "owner"     -> " §6[Owner]§r";
                case "moderator" -> " §b[Mod]§r";
                default          -> "";
            };
            msg("§b[CosmicTeams] Team: §f" + config.getTeamName() + roleLabel +
                    " §7| §bRelay: " + (RelayClient.isConnected() ? "§aConnected" : "§cDisconnected"));
        } else {
            msg("§7[CosmicTeams] Not on a team. " +
                    "Use §f/team create <name>§7 or wait for an invite.");
        }
    }

    /**
     * Sends an inventory-view request to the relay for the given teammate.
     * Requires an active relay connection and team membership.
     * The result (or error) arrives asynchronously via the WebSocket message handler.
     */
    private static void invsee(String target) {
        if (!RelayClient.isConnected()) {
            msg("§c[CosmicTeams] Not connected to relay server."); return;
        }
        CosmicTeamsConfig config = CosmicTeamsConfig.get();
        if (!config.hasTeam()) {
            msg("§c[CosmicTeams] You are not on a team."); return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.getName().getString().equalsIgnoreCase(target)) {
            msg("§c[CosmicTeams] You cannot view your own inventory."); return;
        }
        RelayClient.sendInvSee(target);
        msg("§7[CosmicTeams] Requesting inventory of §f" + target + "§7...");
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    @FunctionalInterface interface ResponseHandler { void handle(int status, String body); }

    static void post(String path, String jsonBody, ResponseHandler handler) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> MinecraftClient.getInstance().execute(
                        () -> handler.handle(r.statusCode(), r.body())))
                .exceptionally(e -> {
                    msg("§c[CosmicTeams] Could not reach server: " + e.getMessage());
                    return null;
                });
    }

    static void get(String path, ResponseHandler handler) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + path))
                .GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(r -> MinecraftClient.getInstance().execute(
                        () -> handler.handle(r.statusCode(), r.body())))
                .exceptionally(e -> null);
    }

    static void msg(String message) {
        MinecraftClient c = MinecraftClient.getInstance();
        if (c.player != null) c.player.sendMessage(Text.literal(message), false);
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private static String extractFromObj(String obj, String key) {
        String tag = "\"" + key + "\":\"";
        int s = obj.indexOf(tag);
        if (s < 0) return "";
        s += tag.length();
        int e = obj.indexOf('"', s);
        return e < 0 ? "" : obj.substring(s, e);
    }

    private static void parseStringArray(String json, String field,
                                         CopyOnWriteArrayList<String> target) {
        String marker = "\"" + field + "\":[";
        int start = json.indexOf(marker);
        if (start < 0) return;
        start += marker.length();
        int end = json.indexOf(']', start);
        if (end < 0) return;
        for (String part : json.substring(start, end).split(",")) {
            String name = part.trim().replaceAll("^\"|\"$", "");
            if (!name.isEmpty()) target.add(name);
        }
    }

    private static String fmt(String template, String... args) {
        Object[] escaped = new String[args.length];
        for (int i = 0; i < args.length; i++)
            escaped[i] = args[i].replace("\\", "\\\\").replace("\"", "\\\"");
        return String.format(template, escaped);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}