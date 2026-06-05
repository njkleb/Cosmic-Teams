package njkleb.cosmicteams.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Handles mod version checking and automatic updating.
 *
 * <p>Flow on mod init ({@link #initialize()}):
 * <ol>
 *   <li>Delete any {@code .disabled} jars left by a previous update's shutdown hook.</li>
 *   <li>Complete any interrupted update from a prior session (e.g. after a crash).</li>
 *   <li>Async-check {@code GET /version} on the relay server.</li>
 * </ol>
 *
 * <p>If the server version is newer:
 * <ol>
 *   <li>Download the new jar as {@code cosmic-teams-X.X.X.jar.pending} (Fabric ignores
 *       files without a {@code .jar} extension).</li>
 *   <li>Write a marker file recording the old and new jar names.</li>
 *   <li>Register a JVM shutdown hook that performs the swap when Minecraft closes.</li>
 *   <li>Notify the player to restart.</li>
 * </ol>
 *
 * <p>The shutdown hook renames {@code .pending} → {@code .jar} and tries to delete the old
 * jar.  If deletion fails on Windows (file lock), it renames the old jar to {@code .disabled}
 * instead — Fabric ignores these files — and the next startup's cleanup removes them.
 */
public class ModUpdater {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String MOD_ID              = "cosmic-teams";
    /** Marker file written to the mods folder when an update is in progress. */
    private static final String MARKER_FILENAME     = "cosmic-teams-updater.txt";

    // ── State ─────────────────────────────────────────────────────────────────

    /** Prevents the version check from running more than once per session. */
    private static volatile boolean updateAlreadyChecked = false;

    /** Set to true once a new jar has been successfully downloaded this session. */
    private static volatile boolean updateDownloaded = false;

    /**
     * Notification message queued while the player is still in the main menu.
     * Displayed on the first world join via {@link #showPendingNotification()}.
     */
    private static volatile String pendingNotification = null;

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Call once from {@code CosmicTeamsClient.onInitializeClient()}.
     * Safe to call from the main thread — all I/O is performed asynchronously.
     */
    public static void initialize() {
        cleanupDisabledJars();
        cleanupOldVersionJars();
        tryCompletePendingUpdate();
        checkForUpdates();
    }

    /**
     * Call from the {@code ClientPlayConnectionEvents.JOIN} handler to display
     * any version-check notification that arrived while the player was in the
     * main menu (where {@code mc.player} is null).
     */
    public static void showPendingNotification() {
        String msg = pendingNotification;
        if (msg == null) return;
        pendingNotification = null;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }

    /** Returns true if a new jar has been downloaded and is pending a restart. */
    public static boolean isUpdatePending() {
        return updateDownloaded;
    }

    // ── Startup cleanup ───────────────────────────────────────────────────────

    /**
     * Deletes any {@code cosmic-teams*.disabled} files left by a previous
     * update's shutdown hook that could not delete the old jar directly (Windows).
     */
    private static void cleanupDisabledJars() {
        Path modsDir = getModsDir();
        try (var stream = Files.list(modsDir)) {
            stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.startsWith("cosmic-teams") && name.endsWith(".disabled");
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            System.out.println("[CosmicTeams] Deleted outdated jar: " + p.getFileName());
                        } catch (IOException e) {
                            System.err.println("[CosmicTeams] Could not delete " + p.getFileName()
                                    + ": " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("[CosmicTeams] Could not scan mods folder for cleanup: " + e.getMessage());
        }
    }

    /**
     * Deletes any {@code cosmic-teams*.jar} files in the mods folder that are
     * NOT the currently-loaded jar.
     *
     * <p>This handles the common Windows case where the shutdown hook successfully
     * renamed the {@code .pending} jar to {@code .jar} but could not delete the old
     * jar because the JVM still held a file lock on it.  By the next launch Fabric
     * has already chosen which version to load, so the old jar's handle is released
     * and we can safely delete it here.
     */
    private static void cleanupOldVersionJars() {
        Path currentJar = getCurrentJarPath();
        if (currentJar == null) return; // dev environment — nothing to do

        Path modsDir = getModsDir();
        try (var stream = Files.list(modsDir)) {
            stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        // Target only cosmic-teams jar files that aren't the one we loaded
                        return name.startsWith("cosmic-teams") && name.endsWith(".jar")
                                && !p.toAbsolutePath().equals(currentJar.toAbsolutePath());
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            System.out.println("[CosmicTeams] Deleted old version jar: " + p.getFileName());
                        } catch (IOException e) {
                            System.err.println("[CosmicTeams] Could not delete old version jar '"
                                    + p.getFileName() + "': " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            System.err.println("[CosmicTeams] Could not scan mods folder for old versions: " + e.getMessage());
        }
    }

    /**
     * If the marker file and a {@code .pending} jar exist from a prior session
     * (typically because Minecraft crashed before the shutdown hook could run),
     * attempts to complete the update now.
     */
    private static void tryCompletePendingUpdate() {
        Path modsDir    = getModsDir();
        Path markerFile = modsDir.resolve(MARKER_FILENAME);
        if (!Files.exists(markerFile)) return;

        try {
            List<String> lines = Files.readAllLines(markerFile);
            if (lines.size() < 2) {
                Files.deleteIfExists(markerFile);
                return;
            }
            String oldJarName     = lines.get(0).trim();
            String pendingJarName = lines.get(1).trim();

            Path oldJar     = modsDir.resolve(oldJarName);
            Path pendingJar = modsDir.resolve(pendingJarName);

            if (!Files.exists(pendingJar)) {
                // The pending file is gone — the update completed successfully on a prior run.
                Files.deleteIfExists(markerFile);
                return;
            }

            // Rename .pending → .jar
            String finalJarName = pendingJarName.replace(".pending", "");
            Path   finalJar     = modsDir.resolve(finalJarName);
            Files.move(pendingJar, finalJar, StandardCopyOption.REPLACE_EXISTING);

            // Remove the old jar
            tryDeleteOrDisable(oldJar);

            Files.deleteIfExists(markerFile);
            System.out.println("[CosmicTeams] Completed deferred update: " + finalJarName);

        } catch (IOException e) {
            System.err.println("[CosmicTeams] Could not complete deferred update: " + e.getMessage());
        }
    }

    // ── Version check ─────────────────────────────────────────────────────────

    private static void checkForUpdates() {
        if (updateAlreadyChecked) return;
        updateAlreadyChecked = true;

        String currentVersion = FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("0.0.0");

        // Derive the HTTP base URL from the WebSocket URL
        String httpBase = RelayClient.SERVER_URL
                .replace("ws://",  "http://")
                .replace("wss://", "https://")
                .replaceAll(":8080$", ":8081");

        HttpClient.newHttpClient()
                .sendAsync(
                        HttpRequest.newBuilder()
                                .uri(URI.create(httpBase + "/version"))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    String json        = response.body();
                    String serverVer   = RelayClient.extractStr(json, "version");
                    String downloadUrl = RelayClient.extractStr(json, "download_url");
                    if (serverVer.isEmpty()) return;

                    if (isNewerVersion(serverVer, currentVersion)) {
                        handleOutdatedClient(currentVersion, serverVer, downloadUrl);
                    }
                })
                .exceptionally(e -> {
                    System.err.println("[CosmicTeams] Version check failed: " + e.getMessage());
                    return null;
                });
    }

    /**
     * Called when the relay server reports a version newer than what the client
     * is running.  If a download URL is provided, begins the auto-update flow.
     * Otherwise, shows a plain notification with a fallback message.
     */
    private static void handleOutdatedClient(String currentVersion,
                                             String serverVersion,
                                             String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isEmpty()) {
            // No download URL configured on the server — show a plain alert
            String msg = "§e[CosmicTeams] §fYour mod is outdated §7(v" + currentVersion
                    + " → v" + serverVersion + ")§f. Please update.";
            deliverNotification(msg);
            return;
        }

        // Announce the download is starting
        String startMsg = "§e[CosmicTeams] §fUpdate available §7(v" + currentVersion
                + " → v" + serverVersion + ")§f. Downloading...";
        deliverNotification(startMsg);

        // Download on a daemon thread so it never blocks the game
        Thread t = new Thread(
                () -> downloadUpdate(downloadUrl, serverVersion, currentVersion),
                "CosmicTeams-Updater");
        t.setDaemon(true);
        t.start();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private static void downloadUpdate(String downloadUrl,
                                       String newVersion,
                                       String currentVersion) {
        Path modsDir    = getModsDir();
        String pendName = "cosmic-teams-" + newVersion + ".jar.pending";
        Path pendPath   = modsDir.resolve(pendName);

        try {
            // -- Fetch the jar ------------------------------------------------
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .GET().build();
            HttpResponse<InputStream> response =
                    HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build()
                            .send(req, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                onDownloadFailed(newVersion, downloadUrl);
                return;
            }

            // Write to .pending — Fabric only loads plain .jar files, so this
            // file is invisible to Fabric until we rename it.
            try (InputStream in = response.body()) {
                Files.copy(in, pendPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // -- Locate the current jar ---------------------------------------
            Path currentJar = getCurrentJarPath();
            if (currentJar == null) {
                // Dev environment or unrecognised path — can't auto-swap,
                // but the download is saved; tell the user where it is.
                onDownloadedButCantSwap(pendPath);
                return;
            }

            // -- Write the marker file ----------------------------------------
            // Records both jar names so the startup cleanup can finish the swap
            // if the shutdown hook doesn't run (e.g. crash).
            Path markerFile = modsDir.resolve(MARKER_FILENAME);
            Files.writeString(markerFile,
                    currentJar.getFileName().toString() + "\n" + pendName,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // -- Register the shutdown hook -----------------------------------
            registerSwapShutdownHook(currentJar, pendPath, markerFile);

            updateDownloaded = true;
            onDownloadSuccess(currentVersion, newVersion);

        } catch (Exception e) {
            System.err.println("[CosmicTeams] Download error: " + e.getMessage());
            try { Files.deleteIfExists(pendPath); } catch (IOException ignored) {}
            onDownloadFailed(newVersion, downloadUrl);
        }
    }

    /**
     * Registers a JVM shutdown hook that swaps the jars when Minecraft closes.
     *
     * <ol>
     *   <li>Rename {@code .pending} → {@code .jar} (never locked by Fabric).</li>
     *   <li>Try to delete the old jar; if that fails on Windows, rename it to
     *       {@code .disabled} so Fabric ignores it next launch.</li>
     *   <li>Delete the marker file.</li>
     * </ol>
     */
    private static void registerSwapShutdownHook(Path oldJar,
                                                 Path pendingJar,
                                                 Path markerFile) {
        String finalName = pendingJar.getFileName().toString().replace(".pending", "");
        Path   finalJar  = pendingJar.resolveSibling(finalName);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                // Step 1: activate the new jar
                Files.move(pendingJar, finalJar, StandardCopyOption.REPLACE_EXISTING);
                // Step 2: remove the old jar (or disable it if locked on Windows)
                tryDeleteOrDisable(oldJar);
                // Step 3: clean up the marker
                Files.deleteIfExists(markerFile);
                System.out.println("[CosmicTeams] Update applied: " + finalName);
            } catch (IOException e) {
                System.err.println("[CosmicTeams] Shutdown hook error: " + e.getMessage());
            }
        }, "CosmicTeams-UpdateApplicator"));
    }

    // ── File utilities ────────────────────────────────────────────────────────

    /**
     * Tries to delete {@code jar}.  If deletion fails — typically on Windows
     * because the JVM keeps an open handle on loaded jars — falls back to
     * renaming the file with a {@code .disabled} extension.  Fabric ignores
     * {@code .disabled} files when scanning the mods folder, so this is safe.
     * The file will be deleted by {@link #cleanupDisabledJars()} on the next
     * Minecraft launch.
     */
    private static void tryDeleteOrDisable(Path jar) {
        if (!Files.exists(jar)) return;
        try {
            Files.delete(jar);
        } catch (IOException deleteEx) {
            Path disabled = jar.resolveSibling(jar.getFileName() + ".disabled");
            try {
                Files.move(jar, disabled, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[CosmicTeams] Old jar marked .disabled "
                        + "(will be removed on next launch): " + jar.getFileName());
            } catch (IOException renameEx) {
                System.err.println("[CosmicTeams] Could not remove or disable old jar '"
                        + jar.getFileName() + "': " + renameEx.getMessage()
                        + ". You may need to delete it manually.");
            }
        }
    }

    /**
     * Returns the filesystem path of the currently-running mod jar via Fabric's
     * mod origin API.  Returns {@code null} in dev environments (where the mod
     * is loaded from a class-file directory rather than a jar) and when the path
     * cannot be determined.
     */
    private static Path getCurrentJarPath() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> {
                    List<Path> paths = container.getOrigin().getPaths();
                    if (paths.isEmpty()) return null;
                    Path p = paths.getFirst();
                    // In a dev environment the origin is a directory, not a jar
                    return Files.isDirectory(p) ? null : p;
                })
                .orElse(null);
    }

    /**
     * Returns the mods folder by looking at where the mod jar was actually loaded
     * from.  This is correct regardless of launcher — Lunar Client, Prism, CurseForge,
     * and vanilla all load the jar from their own profile-specific mods folder, so
     * {@code currentJar.getParent()} is always the right place to write the update.
     *
     * <p>Falls back to {@code getGameDir()/mods} only in dev environments where the
     * mod is loaded from a class directory rather than a jar (getCurrentJarPath()
     * returns null in that case).
     */
    private static Path getModsDir() {
        Path currentJar = getCurrentJarPath();
        if (currentJar != null) {
            return currentJar.getParent();
        }
        // Dev environment fallback
        return FabricLoader.getInstance().getGameDir().resolve("mods");
    }

    // ── Version comparison ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code a} is strictly newer than {@code b}.
     * Parses both strings as dot-separated integers, e.g. {@code "1.2.3"}.
     * A leading {@code v} and any non-numeric suffix (e.g. {@code -beta}) are
     * stripped before parsing.
     */
    static boolean isNewerVersion(String a, String b) {
        int[] va  = parseVersion(a);
        int[] vb  = parseVersion(b);
        int   len = Math.max(va.length, vb.length);
        for (int i = 0; i < len; i++) {
            int ai = i < va.length ? va[i] : 0;
            int bi = i < vb.length ? vb[i] : 0;
            if (ai != bi) return ai > bi;
        }
        return false; // equal
    }

    private static int[] parseVersion(String v) {
        // Strip leading 'v' and any suffix that starts with a non-numeric/dot char
        v = v.replaceFirst("^v", "").replaceAll("[^0-9.].*$", "");
        String[] parts = v.split("\\.");
        int[]    nums  = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { nums[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException ignored) {}
        }
        return nums;
    }

    // ── Player notifications ──────────────────────────────────────────────────

    /**
     * Delivers a message to the player.  If the player is not yet in a world
     * (main-menu state), queues it for display on the next world join.
     */
    private static void deliverNotification(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal(msg), false);
            } else {
                pendingNotification = msg;
            }
        });
    }

    private static void onDownloadSuccess(String oldVersion, String newVersion) {
        deliverNotification(
                "§a[CosmicTeams] Update downloaded §7(v" + oldVersion
                        + " → v" + newVersion + ")§a! "
                        + "§fClose and reopen Minecraft to apply it.");
    }

    private static void onDownloadFailed(String newVersion, String fallbackUrl) {
        deliverNotification(
                "§c[CosmicTeams] Auto-update failed. "
                        + "Download v" + newVersion + " manually: §b" + fallbackUrl);
    }

    private static void onDownloadedButCantSwap(Path pendingPath) {
        deliverNotification(
                "§e[CosmicTeams] Update saved as §f"
                        + pendingPath.getFileName()
                        + "§e in your mods folder. "
                        + "Rename it to §f.jar§e and delete the old version to apply.");
    }
}