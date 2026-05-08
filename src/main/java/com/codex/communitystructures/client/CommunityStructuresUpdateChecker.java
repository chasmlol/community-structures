package com.codex.communitystructures.client;

import com.codex.communitystructures.CommunityStructures;
import com.google.gson.Gson;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommunityStructuresUpdateChecker {
	private static final Gson GSON = new Gson();
	private static final String RELEASE_API_URL = "https://api.github.com/repos/chasmlol/community-structures/releases/latest";
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(6))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();
	private static final long AUTOMATIC_CHECK_INTERVAL_MILLIS = Duration.ofMinutes(5).toMillis();
	private static final AtomicBoolean CHECK_IN_FLIGHT = new AtomicBoolean();
	private static final AtomicBoolean INSTALL_STARTED = new AtomicBoolean();
	private static volatile ReleaseInfo availableRelease;
	private static volatile String notifiedVersion = "";
	private static volatile long nextAutomaticCheckAtMillis = 0L;

	private CommunityStructuresUpdateChecker() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(ClientCommandManager.literal("communitystructures-update")
				.executes(context -> {
					installLatest(context.getSource().getClient());
					return 1;
				}));
			dispatcher.register(ClientCommandManager.literal("communitystructures-check-update")
				.executes(context -> {
					checkForUpdates(true);
					context.getSource().sendFeedback(Text.literal("Checking Community Structures releases..."));
					return 1;
				}));
		});
		nextAutomaticCheckAtMillis = System.currentTimeMillis() + AUTOMATIC_CHECK_INTERVAL_MILLIS;
		checkForUpdates(false);
	}

	public static void tick(MinecraftClient client) {
		if (client.player != null && System.currentTimeMillis() >= nextAutomaticCheckAtMillis) {
			nextAutomaticCheckAtMillis = System.currentTimeMillis() + AUTOMATIC_CHECK_INTERVAL_MILLIS;
			checkForUpdates(false);
		}
		ReleaseInfo release = availableRelease;
		if (release != null && client.player != null && !release.version().equals(notifiedVersion)) {
			sendUpdateMessageAndRemember(client, release);
		}
	}

	public static void receiveServerUpdate(String version, String htmlUrl, String assetName, String assetUrl) {
		rememberAvailableRelease(new ReleaseInfo(normalizeVersion(version), clean(htmlUrl), clean(assetName), clean(assetUrl)));
	}

	private static void checkForUpdates(boolean forceMessage) {
		if (!CHECK_IN_FLIGHT.compareAndSet(false, true)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				Optional<ReleaseInfo> latest = fetchLatestRelease();
				MinecraftClient client = MinecraftClient.getInstance();
				if (latest.isPresent() && isNewer(latest.get().version(), currentVersion())) {
					rememberAvailableRelease(latest.get());
					if (forceMessage) {
						client.execute(() -> sendUpdateMessageAndRemember(client, latest.get()));
					}
				} else if (forceMessage) {
					client.execute(() -> sendClientMessage(client, Text.literal("Community Structures is up to date.").formatted(Formatting.GREEN)));
				}
			} catch (Exception exception) {
				CommunityStructures.LOGGER.debug("Could not check Community Structures release", exception);
				if (forceMessage) {
					MinecraftClient client = MinecraftClient.getInstance();
					client.execute(() -> sendClientMessage(client, Text.literal("Could not check Community Structures updates.").formatted(Formatting.RED)));
				}
			} finally {
				CHECK_IN_FLIGHT.set(false);
			}
		});
	}

	private static void rememberAvailableRelease(ReleaseInfo release) {
		if (release == null || release.version().isBlank() || release.assetUrl().isBlank() || !isNewer(release.version(), currentVersion())) {
			return;
		}
		ReleaseInfo current = availableRelease;
		if (current == null || current.version().equals(release.version()) || isNewer(release.version(), current.version())) {
			availableRelease = release;
		}
	}

	private static Optional<ReleaseInfo> fetchLatestRelease() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASE_API_URL))
			.timeout(Duration.ofSeconds(10))
			.header("accept", "application/vnd.github+json")
			.header("user-agent", "community-structures-update-checker")
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("GitHub release endpoint returned HTTP " + response.statusCode());
		}
		GitHubRelease release = GSON.fromJson(response.body(), GitHubRelease.class);
		if (release == null || release.tag_name == null || release.assets == null) {
			return Optional.empty();
		}
		return release.assets.stream()
			.filter(asset -> asset != null && asset.browser_download_url != null && asset.name != null)
			.filter(asset -> asset.name.endsWith(".jar") && !asset.name.contains("sources"))
			.max(Comparator.comparingLong(asset -> asset.size))
			.map(asset -> new ReleaseInfo(normalizeVersion(release.tag_name), release.html_url, asset.name, asset.browser_download_url));
	}

	private static void sendUpdateMessage(MinecraftClient client, ReleaseInfo release) {
		Text message = Text.literal("Community Structures " + release.version() + " is available. ")
			.formatted(Formatting.GOLD)
			.append(Text.literal("[Update now]")
				.formatted(Formatting.GREEN, Formatting.UNDERLINE)
				.styled(style -> style
					.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/communitystructures-update"))
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Download the latest jar and apply it after Minecraft closes.")))))
			.append(Text.literal(" ")
				.append(Text.literal("[Release]")
					.formatted(Formatting.AQUA, Formatting.UNDERLINE)
					.styled(style -> style
						.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, release.htmlUrl()))
						.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Open the GitHub release page."))))));
		sendClientMessage(client, message);
	}

	private static void sendUpdateMessageAndRemember(MinecraftClient client, ReleaseInfo release) {
		notifiedVersion = release.version();
		sendUpdateMessage(client, release);
	}

	private static void installLatest(MinecraftClient client) {
		ReleaseInfo release = availableRelease;
		if (release == null) {
			sendClientMessage(client, Text.literal("No Community Structures update is ready. Try /communitystructures-check-update first.").formatted(Formatting.YELLOW));
			return;
		}
		if (!INSTALL_STARTED.compareAndSet(false, true)) {
			sendClientMessage(client, Text.literal("Community Structures update is already downloading.").formatted(Formatting.YELLOW));
			return;
		}
		sendClientMessage(client, Text.literal("Downloading Community Structures " + release.version() + "...").formatted(Formatting.YELLOW));
		CompletableFuture.runAsync(() -> {
			try {
				Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
				Files.createDirectories(modsDir);
				String targetName = release.assetName().isBlank()
					? "community-structures-" + release.version() + ".jar"
					: release.assetName();
				Path target = modsDir.resolve(targetName);
				Path temp = modsDir.resolve(targetName + ".download");
				download(release.assetUrl(), temp);
				Path current = currentJarPath().orElse(target);
				Path script = writeUpdaterScript(current, temp, target);
				launchUpdater(script);
				client.execute(() -> sendClientMessage(client, Text.literal("Update downloaded. Close Minecraft, then start it again to finish installing Community Structures " + release.version() + ".").formatted(Formatting.GREEN)));
			} catch (Exception exception) {
				CommunityStructures.LOGGER.warn("Could not install Community Structures update", exception);
				client.execute(() -> sendClientMessage(client, Text.literal("Community Structures update failed. Check the log for details.").formatted(Formatting.RED)));
				INSTALL_STARTED.set(false);
			}
		});
	}

	private static void download(String url, Path output) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(Duration.ofSeconds(45))
			.header("user-agent", "community-structures-update-checker")
			.GET()
			.build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Release asset returned HTTP " + response.statusCode());
		}
		Path temp = output.resolveSibling(output.getFileName() + ".tmp");
		try (InputStream input = response.body()) {
			Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
		}
		Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	private static Optional<Path> currentJarPath() {
		try {
			URI uri = CommunityStructuresUpdateChecker.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			Path path = Path.of(uri).toAbsolutePath().normalize();
			return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")
				? Optional.of(path)
				: Optional.empty();
		} catch (Exception exception) {
			return Optional.empty();
		}
	}

	private static Path writeUpdaterScript(Path currentJar, Path downloadedJar, Path targetJar) throws IOException {
		Path scriptDir = FabricLoader.getInstance().getConfigDir().resolve("community_structures");
		Files.createDirectories(scriptDir);
		long pid = currentPid();
		if (isWindows()) {
			Path script = scriptDir.resolve("apply-community-structures-update.ps1");
			String body = """
				$ErrorActionPreference = 'SilentlyContinue'
				$pidToWait = %d
				while (Get-Process -Id $pidToWait -ErrorAction SilentlyContinue) {
				  Start-Sleep -Seconds 1
				}
				Start-Sleep -Seconds 1
				Remove-Item -LiteralPath '%s' -Force -ErrorAction SilentlyContinue
				Remove-Item -LiteralPath '%s' -Force -ErrorAction SilentlyContinue
				Move-Item -LiteralPath '%s' -Destination '%s' -Force
				Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force -ErrorAction SilentlyContinue
				""".formatted(pid, escapePowerShell(currentJar), escapePowerShell(targetJar), escapePowerShell(downloadedJar), escapePowerShell(targetJar));
			Files.writeString(script, body);
			return script;
		}

		Path script = scriptDir.resolve("apply-community-structures-update.sh");
		String body = """
			#!/bin/sh
			pid_to_wait=%d
			while kill -0 "$pid_to_wait" 2>/dev/null; do
			  sleep 1
			done
			sleep 1
			rm -f '%s'
			rm -f '%s'
			mv '%s' '%s'
			rm -f "$0"
			""".formatted(pid, escapeSingleQuotes(currentJar), escapeSingleQuotes(targetJar), escapeSingleQuotes(downloadedJar), escapeSingleQuotes(targetJar));
		Files.writeString(script, body);
		script.toFile().setExecutable(true);
		return script;
	}

	private static void launchUpdater(Path script) throws IOException {
		ProcessBuilder builder;
		if (isWindows()) {
			builder = new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString());
		} else {
			builder = new ProcessBuilder("/bin/sh", script.toString());
		}
		builder.start();
	}

	private static void sendClientMessage(MinecraftClient client, Text text) {
		if (client.player != null) {
			client.player.sendMessage(text, false);
		}
	}

	private static String currentVersion() {
		return FabricLoader.getInstance()
			.getModContainer(CommunityStructures.MOD_ID)
			.map(container -> container.getMetadata().getVersion().getFriendlyString())
			.orElse("0.0.0");
	}

	private static boolean isNewer(String candidate, String current) {
		List<Integer> candidateParts = versionParts(candidate);
		List<Integer> currentParts = versionParts(current);
		int max = Math.max(candidateParts.size(), currentParts.size());
		for (int index = 0; index < max; index++) {
			int left = index < candidateParts.size() ? candidateParts.get(index) : 0;
			int right = index < currentParts.size() ? currentParts.get(index) : 0;
			if (left != right) {
				return left > right;
			}
		}
		return false;
	}

	private static List<Integer> versionParts(String version) {
		return java.util.Arrays.stream(normalizeVersion(version).split("[^0-9]+"))
			.filter(part -> !part.isBlank())
			.map(part -> {
				try {
					return Integer.parseInt(part);
				} catch (NumberFormatException exception) {
					return 0;
				}
			})
			.toList();
	}

	private static String normalizeVersion(String version) {
		String cleaned = version == null ? "" : version.trim();
		return cleaned.startsWith("v") || cleaned.startsWith("V") ? cleaned.substring(1) : cleaned;
	}

	private static String clean(String value) {
		return value == null ? "" : value.trim();
	}

	private static long currentPid() {
		try {
			return ProcessHandle.current().pid();
		} catch (Throwable ignored) {
			String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
			int at = runtimeName.indexOf('@');
			if (at > 0) {
				try {
					return Long.parseLong(runtimeName.substring(0, at));
				} catch (NumberFormatException ignoredAgain) {
				}
			}
			return -1L;
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static String escapePowerShell(Path path) {
		return path.toAbsolutePath().normalize().toString().replace("'", "''");
	}

	private static String escapeSingleQuotes(Path path) {
		return path.toAbsolutePath().normalize().toString().replace("'", "'\"'\"'");
	}

	private record ReleaseInfo(String version, String htmlUrl, String assetName, String assetUrl) {
	}

	private static final class GitHubRelease {
		private String tag_name;
		private String html_url;
		private List<GitHubAsset> assets;
	}

	private static final class GitHubAsset {
		private String name;
		private String browser_download_url;
		private long size;
	}
}
