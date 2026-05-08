package com.codex.communitystructures;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CommunityStructureCache {
	private static final Gson GSON = new Gson();

	private final CommunityStructureConfig config;
	private final Path cacheRoot;
	private final HttpClient client;
	private final Map<StructureCategory, CopyOnWriteArrayList<CachedStructure>> cached = new ConcurrentHashMap<>();
	private final Set<String> reserved = ConcurrentHashMap.newKeySet();
	private final Map<StructureCategory, String> lastUsed = new ConcurrentHashMap<>();
	private final AtomicBoolean prefetchQueued = new AtomicBoolean();
	private volatile RequestIdentity requestIdentity = RequestIdentity.EMPTY;
	private ScheduledExecutorService executor;

	public CommunityStructureCache(CommunityStructureConfig config) {
		this.config = config;
		this.cacheRoot = FabricLoader.getInstance().getConfigDir().resolve("community_structures").resolve("cache");
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(4))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

		for (StructureCategory category : StructureCategory.values()) {
			cached.put(category, new CopyOnWriteArrayList<>());
		}
	}

	public void start() {
		try {
			Files.createDirectories(cacheRoot);
			loadExisting();
		} catch (IOException exception) {
			CommunityStructures.LOGGER.warn("Could not prepare structure cache", exception);
		}

		ThreadFactory factory = task -> {
			Thread thread = new Thread(task, "Community Structures Downloader");
			thread.setDaemon(true);
			return thread;
		};
		executor = Executors.newSingleThreadScheduledExecutor(factory);
		if (config.refillCacheAfterUse) {
			executor.schedule(this::prefetchAll, 5, TimeUnit.SECONDS);
			executor.scheduleAtFixedRate(this::prefetchAll, config.downloadIntervalSeconds, config.downloadIntervalSeconds, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (executor != null) {
			executor.shutdownNow();
			executor = null;
		}
	}

	public void updateIdentity(MinecraftServer server) {
		List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
		if (players.isEmpty()) {
			return;
		}
		ServerPlayerEntity player = players.get(0);
		requestIdentity = new RequestIdentity(player.getUuidAsString(), player.getGameProfile().getName());
	}

	public Optional<CachedStructure> choose(StructureCategory category, net.minecraft.util.math.random.Random random) {
		return choose(category, random, "");
	}

	public Optional<CachedStructure> choose(StructureCategory category, net.minecraft.util.math.random.Random random, String biomeId) {
		return choose(category, null, random, biomeId);
	}

	public Optional<CachedStructure> choose(StructureCategory category, PlacementPreset preset, net.minecraft.util.math.random.Random random, String biomeId) {
		List<CachedStructure> choices = cached.getOrDefault(category, new CopyOnWriteArrayList<>());
		if (choices.isEmpty()) {
			prefetchSoon();
			return Optional.empty();
		}
		List<CachedStructure> eligible = choices.stream()
			.filter(choice -> choice.canSpawnIn(biomeId))
			.filter(choice -> preset == null || choice.placementPreset() == preset)
			.filter(choice -> !reserved.contains(choice.id()))
			.toList();
		if (eligible.isEmpty()) {
			prefetchSoon();
			return Optional.empty();
		}

		int start = random.nextInt(eligible.size());
		for (int offset = 0; offset < eligible.size(); offset++) {
			CachedStructure choice = eligible.get((start + offset) % eligible.size());
			if (choice.canSpawnIn(biomeId) && reserved.add(choice.id())) {
				return Optional.of(choice);
			}
		}
		if (config.refillCacheAfterUse) {
			prefetchSoon();
		}
		return Optional.empty();
	}

	public synchronized Optional<CachedStructure> materializeForWorldgen(CachedStructure structure) {
		if (!Files.exists(structure.path())) {
			reserved.remove(structure.id());
			prefetchSoon();
			return Optional.empty();
		}

		try {
			Path generatedDir = cacheRoot.resolve("generated").resolve(structure.category().apiName());
			Files.createDirectories(generatedDir);
			Path output = generatedDir.resolve(structure.id() + "-" + UUID.randomUUID() + ".nbt");
			try {
				Files.move(structure.path(), output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicMoveException) {
				Files.move(structure.path(), output, StandardCopyOption.REPLACE_EXISTING);
			}

			CopyOnWriteArrayList<CachedStructure> list = cached.get(structure.category());
			if (list != null) {
				list.removeIf(existing -> existing.id().equals(structure.id()));
			}
			reserved.remove(structure.id());
			lastUsed.put(structure.category(), structure.id());
			Files.deleteIfExists(metadataPath(structure.path()));
			CommunityStructurePlacer.forgetPrepared(structure.path());
			prefetchSoon();

			return Optional.of(new CachedStructure(structure.category(), structure.id(), structure.name(), output, structure.allowedBiomes(), structure.placementPreset(), structure.creatorName(), structure.creatorId()));
		} catch (IOException exception) {
			reserved.remove(structure.id());
			CommunityStructures.LOGGER.warn("Could not prepare generated copy of community structure {}", structure.name(), exception);
			prefetchSoon();
			return Optional.empty();
		}
	}

	public void markUsed(CachedStructure structure) {
		CopyOnWriteArrayList<CachedStructure> list = cached.get(structure.category());
		if (list != null) {
			list.removeIf(existing -> existing.id().equals(structure.id()));
		}
		reserved.remove(structure.id());
		lastUsed.put(structure.category(), structure.id());
		try {
			Files.deleteIfExists(structure.path());
			Files.deleteIfExists(metadataPath(structure.path()));
			CommunityStructurePlacer.forgetPrepared(structure.path());
		} catch (IOException exception) {
			CommunityStructures.LOGGER.debug("Could not remove used cached structure {}", structure.path(), exception);
		}
		prefetchSoon();
	}

	public void release(CachedStructure structure) {
		reserved.remove(structure.id());
	}

	public void prefetchSoon() {
		if (config.refillCacheAfterUse && executor != null && prefetchQueued.compareAndSet(false, true)) {
			executor.execute(() -> {
				try {
					prefetchAll();
				} finally {
					prefetchQueued.set(false);
				}
			});
		}
	}

	private void prefetchAll() {
		if (!config.enabled) {
			return;
		}

		for (StructureCategory category : StructureCategory.values()) {
			if (!isCategoryEnabled(category)) {
				continue;
			}
			try {
				refreshCategory(category);
				fillCategory(category);
				prune(category);
			} catch (Exception exception) {
				CommunityStructures.LOGGER.debug("Could not prefetch {} structure", category.apiName(), exception);
			}
		}
	}

	private void fillCategory(StructureCategory category) throws IOException, InterruptedException {
		if (!isCategoryEnabled(category)) {
			return;
		}
		int attempts = Math.max(config.cachePerCategory * 4, 4);
		for (int attempt = 0; attempt < attempts && cached.get(category).size() < config.cachePerCategory; attempt++) {
			downloadRandom(category);
		}
	}

	private boolean isCategoryEnabled(StructureCategory category) {
		return switch (category) {
			case LAND -> config.landChancePerChunk > 0.0D;
			case WATER -> config.waterChancePerChunk > 0.0D;
			case CAVE -> config.caveChancePerChunk > 0.0D;
		};
	}

	private void loadExisting() throws IOException {
		for (StructureCategory category : StructureCategory.values()) {
			Path categoryDir = categoryDir(category);
			Files.createDirectories(categoryDir);
			try (var files = Files.list(categoryDir)) {
				List<CachedStructure> existing = files
					.filter(path -> path.getFileName().toString().endsWith(".nbt"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.map(path -> {
						CacheMetadata metadata = readMetadata(path);
						return new CachedStructure(category, stripExtension(path.getFileName().toString()), path.getFileName().toString(), path, metadata.allowedBiomes(), PlacementPreset.fromApiName(metadata.placementPreset(), category), metadata.creatorName(), metadata.creatorId());
					})
					.toList();
				cached.get(category).clear();
				cached.get(category).addAll(existing);
				if (config.legacyChunkEventPlacement) {
					existing.forEach(structure -> CommunityStructurePlacer.prepareAsync(structure, config.maxDownloadBytes));
				}
			}
		}
	}

	private void refreshCategory(StructureCategory category) throws IOException, InterruptedException {
		for (CachedStructure structure : List.copyOf(cached.getOrDefault(category, new CopyOnWriteArrayList<>()))) {
			RemoteStructure remote = fetchStructure(structure.id());
			if (remote == null) {
				removeCached(structure);
				continue;
			}

			StructureCategory remoteCategory = StructureCategory.fromApiName(remote.category()).orElse(category);
			if (remoteCategory != category) {
				moveCached(structure, remoteCategory, remote);
				continue;
			}

			Set<String> allowedBiomes = allowedBiomes(remote);
			PlacementPreset placementPreset = placementPreset(remote, category);
			String creatorName = creatorName(remote);
			String creatorId = creatorId(remote);
			if (!allowedBiomes.equals(structure.allowedBiomes()) || placementPreset != structure.placementPreset() || !creatorName.equals(nullToEmpty(structure.creatorName())) || !creatorId.equals(nullToEmpty(structure.creatorId()))) {
				writeMetadata(structure.path(), remote);
				replaceRemembered(category, new CachedStructure(category, remote.id(), fallbackName(remote), structure.path(), allowedBiomes, placementPreset, creatorName, creatorId));
			}
		}
	}

	private RemoteStructure fetchStructure(String id) throws IOException, InterruptedException {
		URI uri = apiUri("/api/structures/" + URLEncoder.encode(id, StandardCharsets.UTF_8));
		HttpRequest request = requestBuilder(uri)
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.header("accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() == 404) {
			return null;
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Structure endpoint returned HTTP " + response.statusCode());
		}
		return GSON.fromJson(response.body(), RemoteStructure.class);
	}

	private void downloadRandom(StructureCategory category) throws IOException, InterruptedException {
		URI randomUri = apiUri("/api/structures/random?category=" + category.apiName() + excludeQuery(category));
		HttpRequest randomRequest = requestBuilder(randomUri)
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.header("accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> randomResponse = client.send(randomRequest, HttpResponse.BodyHandlers.ofString());
		if (randomResponse.statusCode() == 404) {
			return;
		}
		if (randomResponse.statusCode() / 100 != 2) {
			throw new IOException("Random endpoint returned HTTP " + randomResponse.statusCode());
		}

		RemoteStructure remote = GSON.fromJson(randomResponse.body(), RemoteStructure.class);
		if (remote == null || remote.id() == null || remote.downloadUrl() == null) {
			throw new IOException("Random endpoint returned an invalid structure record");
		}

		Path output = categoryDir(category).resolve(remote.id() + ".nbt");
		if (Files.exists(output)) {
			remember(category, new CachedStructure(category, remote.id(), fallbackName(remote), output, allowedBiomes(remote), placementPreset(remote, category), creatorName(remote), creatorId(remote)));
			return;
		}

		URI downloadUri = apiUri(remote.downloadUrl());
		HttpRequest downloadRequest = requestBuilder(downloadUri)
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(20))
			.GET()
			.build();
		HttpResponse<InputStream> response = client.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Download endpoint returned HTTP " + response.statusCode());
		}
		if (response.headers().firstValueAsLong("content-length").orElse(0L) > config.maxDownloadBytes) {
			throw new IOException("Structure is larger than maxDownloadBytes");
		}

		Files.createDirectories(output.getParent());
		Path temp = output.resolveSibling(output.getFileName() + ".tmp");
		try (InputStream input = response.body()) {
			copyWithLimit(input, temp, config.maxDownloadBytes);
		}
		Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		writeMetadata(output, remote);
		remember(category, new CachedStructure(category, remote.id(), fallbackName(remote), output, allowedBiomes(remote), placementPreset(remote, category), creatorName(remote), creatorId(remote)));
		CommunityStructures.LOGGER.info("Cached {} community structure {}", category.apiName(), fallbackName(remote));
	}

	private void remember(StructureCategory category, CachedStructure structure) {
		CopyOnWriteArrayList<CachedStructure> list = cached.get(category);
		if (list.stream().noneMatch(existing -> existing.id().equals(structure.id()))) {
			list.add(structure);
			if (config.legacyChunkEventPlacement) {
				CommunityStructurePlacer.prepareAsync(structure, config.maxDownloadBytes);
			}
		}
	}

	private void replaceRemembered(StructureCategory category, CachedStructure structure) {
		CopyOnWriteArrayList<CachedStructure> list = cached.get(category);
		list.removeIf(existing -> existing.id().equals(structure.id()));
		list.add(structure);
		if (config.legacyChunkEventPlacement) {
			CommunityStructurePlacer.prepareAsync(structure, config.maxDownloadBytes);
		}
	}

	private void removeCached(CachedStructure structure) throws IOException {
		CopyOnWriteArrayList<CachedStructure> list = cached.get(structure.category());
		if (list != null) {
			list.removeIf(existing -> existing.id().equals(structure.id()));
		}
		reserved.remove(structure.id());
		Files.deleteIfExists(structure.path());
		Files.deleteIfExists(metadataPath(structure.path()));
		CommunityStructurePlacer.forgetPrepared(structure.path());
	}

	private void moveCached(CachedStructure structure, StructureCategory newCategory, RemoteStructure remote) throws IOException {
		CopyOnWriteArrayList<CachedStructure> oldList = cached.get(structure.category());
		if (oldList != null) {
			oldList.removeIf(existing -> existing.id().equals(structure.id()));
		}
		Files.createDirectories(categoryDir(newCategory));
		Path movedPath = categoryDir(newCategory).resolve(structure.id() + ".nbt");
		CommunityStructurePlacer.forgetPrepared(structure.path());
		Files.move(structure.path(), movedPath, StandardCopyOption.REPLACE_EXISTING);
		Files.deleteIfExists(metadataPath(structure.path()));
		writeMetadata(movedPath, remote);
		reserved.remove(structure.id());
		remember(newCategory, new CachedStructure(newCategory, remote.id(), fallbackName(remote), movedPath, allowedBiomes(remote), placementPreset(remote, newCategory), creatorName(remote), creatorId(remote)));
		CommunityStructures.LOGGER.info("Moved cached community structure {} from {} to {}", fallbackName(remote), structure.category().apiName(), newCategory.apiName());
	}

	private String excludeQuery(StructureCategory category) {
		Set<String> excluded = new HashSet<>();
		for (CachedStructure structure : cached.getOrDefault(category, new CopyOnWriteArrayList<>())) {
			excluded.add(structure.id());
		}
		String last = lastUsed.get(category);
		if (last != null) {
			excluded.add(last);
		}
		if (excluded.isEmpty()) {
			return "";
		}
		return "&exclude=" + URLEncoder.encode(String.join(",", excluded), StandardCharsets.UTF_8);
	}

	private void prune(StructureCategory category) throws IOException {
		List<Path> files = new ArrayList<>();
		try (var stream = Files.list(categoryDir(category))) {
			stream.filter(path -> path.getFileName().toString().endsWith(".nbt")).forEach(files::add);
		}

		files.sort(Comparator.comparingLong(this::modifiedTime).reversed());
		for (int index = config.cachePerCategory; index < files.size(); index++) {
			CommunityStructurePlacer.forgetPrepared(files.get(index));
			Files.deleteIfExists(files.get(index));
			Files.deleteIfExists(metadataPath(files.get(index)));
		}
		loadExisting();
	}

	private CacheMetadata readMetadata(Path structurePath) {
		Path metadataPath = metadataPath(structurePath);
		if (!Files.exists(metadataPath)) {
			return new CacheMetadata(Set.of(), "", "", "");
		}
		try (var reader = Files.newBufferedReader(metadataPath)) {
			CacheMetadata metadata = GSON.fromJson(reader, CacheMetadata.class);
			if (metadata == null || metadata.allowedBiomes() == null) {
				return new CacheMetadata(Set.of(), "", "", "");
			}
			return metadata;
		} catch (IOException exception) {
			CommunityStructures.LOGGER.debug("Could not read cache metadata {}", metadataPath, exception);
			return new CacheMetadata(Set.of(), "", "", "");
		}
	}

	private void writeMetadata(Path structurePath, RemoteStructure remote) throws IOException {
		Path metadataPath = metadataPath(structurePath);
		try (var writer = Files.newBufferedWriter(metadataPath)) {
			StructureCategory category = StructureCategory.fromApiName(remote.category()).orElse(StructureCategory.LAND);
			GSON.toJson(new CacheMetadata(allowedBiomes(remote), placementPreset(remote, category).apiName(), creatorName(remote), creatorId(remote)), writer);
		}
	}

	private Path metadataPath(Path structurePath) {
		return structurePath.resolveSibling(stripExtension(structurePath.getFileName().toString()) + ".json");
	}

	private static Set<String> allowedBiomes(RemoteStructure remote) {
		if (remote.allowedBiomes() == null || remote.allowedBiomes().isEmpty()) {
			return Set.of();
		}
		return Set.copyOf(remote.allowedBiomes());
	}

	private static PlacementPreset placementPreset(RemoteStructure remote, StructureCategory category) {
		return PlacementPreset.fromApiName(remote.placementPreset(), category);
	}

	private long modifiedTime(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return 0L;
		}
	}

	private Path categoryDir(StructureCategory category) {
		return cacheRoot.resolve(category.apiName());
	}

	private HttpRequest.Builder requestBuilder(URI uri) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.header("x-community-structures-client", "fabric");
		RequestIdentity identity = requestIdentity;
		if (!identity.playerId().isBlank()) {
			builder.header("x-player-id", identity.playerId());
		}
		if (!identity.playerName().isBlank()) {
			builder.header("x-player-name", identity.playerName());
		}
		return builder;
	}

	private URI apiUri(String pathOrUrl) {
		if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
			return URI.create(pathOrUrl);
		}
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		String path = pathOrUrl.startsWith("/") ? pathOrUrl : "/" + pathOrUrl;
		return URI.create(base + path);
	}

	private static void copyWithLimit(InputStream input, Path output, int maxBytes) throws IOException {
		byte[] buffer = new byte[8192];
		int total = 0;
		try (var out = Files.newOutputStream(output)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new IOException("Structure is larger than maxDownloadBytes");
				}
				out.write(buffer, 0, read);
			}
		}
	}

	private static String stripExtension(String filename) {
		return filename.endsWith(".nbt") ? filename.substring(0, filename.length() - 4) : filename;
	}

	private static String fallbackName(RemoteStructure remote) {
		return remote.name() == null || remote.name().isBlank() ? remote.id() : remote.name();
	}

	private static String creatorName(RemoteStructure remote) {
		return remote.creatorName() == null ? "" : remote.creatorName();
	}

	private static String creatorId(RemoteStructure remote) {
		return remote.creatorId() == null ? "" : remote.creatorId();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private record CacheMetadata(Set<String> allowedBiomes, String placementPreset, String creatorName, String creatorId) {
	}

	private record RequestIdentity(String playerId, String playerName) {
		private static final RequestIdentity EMPTY = new RequestIdentity("", "");
	}
}
