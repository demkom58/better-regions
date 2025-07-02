package io.invokegs.betterregions.update;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles checking for plugin updates from Modrinth.
 */
public final class UpdateChecker {

    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/better-regions/version";
    public static final String PROJECT_URL = "https://modrinth.com/project/better-regions";

    public static class SemVer {
        private final int major;
        private final int minor;
        private final int patch;

        public SemVer(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        public static SemVer fromString(String version) {
            String[] parts = version.replaceAll("^v", "").split("[-_ ]")[0].split("\\.");
            return new SemVer(
                    parts.length > 0 ? Integer.parseInt(parts[0]) : 0,
                    parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0
            );
        }

        public boolean isNewerThan(SemVer other) {
            if (this.major != other.major) return this.major > other.major;
            if (this.minor != other.minor) return this.minor > other.minor;
            return this.patch > other.patch;
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            SemVer semVer = (SemVer) o;
            return major == semVer.major && minor == semVer.minor && patch == semVer.patch;
        }

        @Override
        public int hashCode() {
            return Objects.hash(major, minor, patch);
        }
    }
    
    /**
     * Represents a Modrinth version response.
     */
    public static class ModrinthVersion {
        @SerializedName("game_versions")
        public @Nullable List<String> gameVersions;

        public @Nullable List<String> loaders;

        @SerializedName("version_number")
        public SemVer versionNumber;

        @SerializedName("version_type")
        public String versionType;

        public @Nullable List<ModrinthFile> files;

        @SerializedName("date_published")
        public ZonedDateTime datePublished;

        /**
         * Represents a file in a Modrinth version.
         */
        public static class ModrinthFile {
            public String url;
            public String ename;
            public boolean primary;
            public long size;
        }
    }

    private final Plugin plugin;

    private final SemVer currentVersion;
    private final String serverVersion;
    private final String currentPlatform;
    private final Gson gson;
    private final HttpClient httpClient;

    private volatile SemVer latestVersion;
    private volatile boolean updateAvailable;
    private volatile long lastCheckTime;
    private volatile @Nullable String currentVersionType;

    public UpdateChecker(Plugin plugin) {
        this.plugin = plugin;

        this.currentVersion = SemVer.fromString(plugin.getDescription().getVersion());
        this.serverVersion = Bukkit.getVersion();
        this.currentPlatform = detectCurrentPlatform();
        this.gson = new GsonBuilder()
                .registerTypeAdapter(ZonedDateTime.class, (JsonDeserializer<ZonedDateTime>) (json, type, jsonDeserializationContext) -> ZonedDateTime.parse(json.getAsJsonPrimitive().getAsString()))
                .registerTypeAdapter(SemVer.class, (JsonDeserializer<Object>) (json, typeOfT, context) -> SemVer.fromString(json.getAsString()))
                .create();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.lastCheckTime = 0;

        plugin.getLogger().info("Update checker initialized - Platform: " + currentPlatform +
                ", Current version: " + currentVersion);
    }

    /**
     * Detects the current server platform.
     */
    private String detectCurrentPlatform() {
        String version = Bukkit.getVersionMessage().toLowerCase();
        if (version.contains("purpur")) return "purpur";
        if (version.contains("paper")) return "paper";
        if (version.contains("spigot")) return "spigot";
        return "bukkit";
    }

    /**
     * Checks for updates asynchronously.
     * @return CompletableFuture that completes when the check is done
     */
    public CompletableFuture<Void> checkForUpdates() {
        return CompletableFuture.runAsync(() -> {
            try {
                performUpdateCheck();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to check for updates", e);
            }
        });
    }

    /**
     * Checks for updates and notifies console if enabled.
     * @param notifyConsole whether to log update information to console
     * @return CompletableFuture that completes when the check is done
     */
    public CompletableFuture<Void> checkForUpdates(boolean notifyConsole) {
        return CompletableFuture.runAsync(() -> {
            try {
                performUpdateCheck();
                if (notifyConsole) {
                    logUpdateStatus();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to check for updates", e);
            }
        });
    }

    /**
     * Performs the actual update check by querying Modrinth API.
     */
    private void performUpdateCheck() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(MODRINTH_API_URL))
                .header("User-Agent", "BetterRegions/" + currentVersion + " (https://github.com/demkom58/better-regions)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Modrinth API returned status code: " + response.statusCode());
        }

        parseVersionResponse(response.body());
        this.lastCheckTime = System.currentTimeMillis();
    }

    /**
     * Parses the JSON response from Modrinth API and finds compatible versions.
     */
    private void parseVersionResponse(String responseBody) {
        ModrinthVersion[] versions = gson.fromJson(responseBody, ModrinthVersion[].class);
        Arrays.sort(versions, (v1, v2) -> v2.datePublished.compareTo(v1.datePublished));
        if (versions.length == 0) {
            plugin.getLogger().warning("No versions found on Modrinth for BetterRegions");
            return;
        }

        String currentMinecraftVersion = extractMinecraftVersion();
        if (currentMinecraftVersion == null || currentMinecraftVersion.isEmpty()) {
            plugin.getLogger().warning("Could not determine current Minecraft version from server");
            return;
        }

        this.currentVersionType = findCurrentVersionType(versions);
        ModrinthVersion latestCompatible = findLatestCompatibleVersion(versions, currentMinecraftVersion);
        if (latestCompatible == null) {
            return;
        }

        this.latestVersion = latestCompatible.versionNumber;
        this.updateAvailable = latestVersion.isNewerThan(currentVersion);

        if (updateAvailable) {
            plugin.getLogger().info("Compatible update found: " + latestVersion +
                    " (" + latestCompatible.versionType + ") - current: " + currentVersion);
        }
    }

    /**
     * Finds the current version in the API response to get its actual version_type.
     */
    private String findCurrentVersionType(ModrinthVersion[] versions) {
        for (ModrinthVersion version : versions) {
            if (currentVersion.equals(version.versionNumber)) {
                return version.versionType;
            }
        }

        plugin.getLogger().info("Current version " + currentVersion
                + " not found in Modrinth API, assuming development version");
        return "release";
    }

    /**
     * Finds the latest compatible version based on current version type and compatibility.
     */
    private @Nullable ModrinthVersion findLatestCompatibleVersion(ModrinthVersion[] versions, String minecraftVersion) {
        boolean isCurrentPreRelease = currentVersionType != null && !"release".equals(currentVersionType);
        String preferredType = isCurrentPreRelease ? null : "release";

        for (ModrinthVersion version : versions) {
            if (isVersionCompatible(version, minecraftVersion)) {
                if (preferredType == null || preferredType.equals(version.versionType)) {
                    return version;
                }
            }
        }

        // If no preferred type found and current is release, try any compatible version
        if (!isCurrentPreRelease) {
            for (ModrinthVersion version : versions) {
                if (isVersionCompatible(version, minecraftVersion)) {
                    plugin.getLogger().info("No compatible release found, using " + version.versionType + " version as fallback");
                    return version;
                }
            }
        }

        return null;
    }

    /**
     * Checks if a version is compatible with current server setup.
     */
    private boolean isVersionCompatible(ModrinthVersion version, String minecraftVersion) {
        boolean platformCompatible = version.loaders != null &&
                version.loaders.stream().anyMatch(loader -> loader.equalsIgnoreCase(currentPlatform));

        if (!platformCompatible) return false;
        return version.gameVersions != null && version.gameVersions.contains(minecraftVersion);
    }

    private @Nullable String extractMinecraftVersion() {
        String version = null;

        try {
            version = Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError ignored) {}

        if (version == null || version.isEmpty()) {
            String fullVersion = Bukkit.getVersion();
            Pattern pattern = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)");
            Matcher matcher = pattern.matcher(fullVersion);
            if (matcher.find()) {
                version = matcher.group(1);
            }
        }

        return version;
    }

    /**
     * Logs update status to console.
     */
    private void logUpdateStatus() {
        if (updateAvailable) {
            plugin.getLogger().info("Compatible update available! Current: " + currentVersion + ", Latest: " + latestVersion);
            plugin.getLogger().info("Download from: " + PROJECT_URL);
        } else {
            plugin.getLogger().info("BetterRegions is up to date! (v" + currentVersion + ")");
        }
    }

    /**
     * Notifies a player about available updates.
     * @param sender the player to notify
     */
    public void notifySender(CommandSender sender) {
        if (!updateAvailable) {
            return;
        }

        var updateMessage =
                Component.text()
                        .append(Component.text("[BetterRegions] ", NamedTextColor.AQUA, TextDecoration.BOLD))
                        .append(Component.text("Update available! ", NamedTextColor.YELLOW))
                        .append(Component.text("v" + currentVersion, NamedTextColor.RED))
                        .append(Component.text(" → ", NamedTextColor.GRAY))
                        .append(Component.text("v" + latestVersion, NamedTextColor.GREEN))
                        .appendNewline()
                        .append(Component.text("Click here to download: ", NamedTextColor.GRAY))
                        .append(Component.text("[Modrinth]", NamedTextColor.GREEN, TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl(PROJECT_URL)))
                        .build();

        sender.sendMessage(updateMessage);
    }

    /**
     * Forces an immediate update check.
     * @return CompletableFuture that completes when the check is done
     */
    public CompletableFuture<Boolean> forceCheck() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                performUpdateCheck();
                return updateAvailable;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to check for updates", e);
                return false;
            }
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public SemVer getCurrentVersion() {
        return currentVersion;
    }

    public SemVer getLatestVersion() {
        return latestVersion;
    }

    public long getLastCheckTime() {
        return lastCheckTime;
    }

    public String getCurrentPlatform() {
        return currentPlatform;
    }

    public @Nullable String getCurrentVersionType() {
        return currentVersionType;
    }

    /**
     * Checks if enough time has passed since the last check to perform another one.
     * @param cooldownMinutes minimum minutes between checks
     * @return true if enough time has passed
     */
    public boolean shouldCheck(int cooldownMinutes) {
        return System.currentTimeMillis() - lastCheckTime > (cooldownMinutes * 60 * 1000L);
    }
}