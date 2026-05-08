package com.codex.communitystructures;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.server.world.ServerWorld;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public final class CommunityStructurePlacer {
	private static final Queue<PendingPlacement> PENDING = new ConcurrentLinkedQueue<>();
	private static final Queue<ActivePlacement> ACTIVE = new ConcurrentLinkedQueue<>();
	private static final Set<Long> QUEUED_CHUNKS = ConcurrentHashMap.newKeySet();
	private static final int BULK_PLACE_FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
	private static final int MAX_PENDING_PLACEMENTS = 96;
	private static final int TERRAIN_SAMPLE_STEP = 4;
	private static final int RECENT_PLACEMENT_LIMIT = 256;
	private static final int LAND_SALT = 0x18f2a6b1;
	private static final int WATER_SALT = 0x2b7e1516;
	private static final int CAVE_SALT = 0x3c6ef372;
	private static final int LAND_BASE_SCAN_HEIGHT = 18;
	private static final LandBlendPlan NO_LAND_BLEND = new LandBlendPlan(0, 0, Map.of(), Set.of(), Set.of());
	private static final Queue<ChunkPos> RECENT_PLACEMENTS = new ConcurrentLinkedQueue<>();
	private static final Map<Path, StructureTemplate> TEMPLATE_CACHE = new ConcurrentHashMap<>();
	private static final Map<Path, CompletableFuture<StructureSnapshot>> SNAPSHOT_CACHE = new ConcurrentHashMap<>();
	private static final ExecutorService PREPARE_EXECUTOR = Executors.newSingleThreadExecutor(preparationThreadFactory());
	private static int debugChunkEvents;
	private static int debugPlacementEvents;

	private CommunityStructurePlacer() {
	}

	public static void tryPlace(ServerWorld world, WorldChunk chunk) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (!config.enabled || world.getRegistryKey() != World.OVERWORLD) {
			return;
		}

		ChunkPos chunkPos = chunk.getPos();
		if (debugChunkEvents < 25) {
			CommunityStructures.LOGGER.info("Considering generated chunk {}, {} for community structure", chunkPos.x, chunkPos.z);
			debugChunkEvents++;
		}
		if (isNearSpawn(world, chunkPos, config.spawnProtectionRadiusChunks)) {
			CommunityStructures.LOGGER.debug("Skipping community structure near spawn at chunk {}, {}", chunkPos.x, chunkPos.z);
			return;
		}
		if (isNearRecentPlacement(chunkPos, config.minPlacementDistanceChunks)) {
			CommunityStructures.LOGGER.debug("Skipping community structure near recent placement at chunk {}, {}", chunkPos.x, chunkPos.z);
			return;
		}

		List<PlacementCandidate> candidates = placementCandidates(chunkPos, world.getSeed(), config);
		if (candidates.isEmpty()) {
			CommunityStructures.LOGGER.debug("Chunk {}, {} is not a community structure spread candidate", chunkPos.x, chunkPos.z);
			return;
		}

		String biome = biomeId(world, chunkPos);
		for (PlacementCandidate candidate : candidates) {
			Random random = Random.create(candidate.seed());
			StructureCategory category = candidate.category();
			CommunityStructures.cache().choose(category, random, biome).ifPresentOrElse(
				structure -> {
					logPlacementDebug("Queued {} community structure {} for chunk {}, {}", category.apiName(), structure.name(), chunkPos.x, chunkPos.z);
					enqueue(chunkPos, category, structure, candidate.seed());
				},
				() -> CommunityStructures.LOGGER.debug("No cached {} community structure available for chunk {}, {}", category.apiName(), chunkPos.x, chunkPos.z)
			);
		}
	}

	public static void tick(ServerWorld world) {
		if (world.getRegistryKey() != World.OVERWORLD) {
			return;
		}

		CommunityStructureConfig config = CommunityStructures.config();
		if (processActivePlacement(world, config)) {
			return;
		}

		int checked = 0;
		while (checked < config.maxPlacementsPerTick) {
			PendingPlacement pending = PENDING.poll();
			if (pending == null) {
				return;
			}

			QUEUED_CHUNKS.remove(placementKey(pending.chunkPos(), pending.category()));
			PlacementOutcome outcome = place(world, pending);
			if (outcome == PlacementOutcome.RETRY) {
				retry(pending);
			}
			checked++;
		}
	}

	static void prepareAsync(CachedStructure structure, int maxBytes) {
		SNAPSHOT_CACHE.computeIfAbsent(structure.path(), path -> CompletableFuture.supplyAsync(() -> {
			try {
				CommunityStructures.LOGGER.debug("Preparing community structure {} off-thread", structure.name());
				return readStructureSnapshot(structure, maxBytes);
			} catch (IOException exception) {
				throw new IllegalStateException("Could not prepare community structure " + structure.path(), exception);
			}
		}, PREPARE_EXECUTOR));
	}

	static void forgetPrepared(Path path) {
		SNAPSHOT_CACHE.remove(path);
		TEMPLATE_CACHE.remove(path);
	}

	static void shutdown() {
		PREPARE_EXECUTOR.shutdownNow();
	}

	private static void enqueue(ChunkPos chunkPos, StructureCategory category, CachedStructure structure, long seed) {
		if (PENDING.size() >= MAX_PENDING_PLACEMENTS) {
			CommunityStructures.LOGGER.debug("Dropping queued {} community structure {} because the placement backlog is full", category.apiName(), structure.name());
			CommunityStructures.cache().release(structure);
			return;
		}

		long chunkKey = placementKey(chunkPos, category);
		if (QUEUED_CHUNKS.add(chunkKey)) {
			PENDING.add(new PendingPlacement(chunkPos, category, structure, seed, 0));
		} else {
			CommunityStructures.cache().release(structure);
		}
	}

	private static void retry(PendingPlacement pending) {
		if (pending.attempts() >= CommunityStructures.config().maxPendingPlacementAttempts) {
			CommunityStructures.LOGGER.info("Gave up waiting for loaded footprint for community structure {} after {} attempts", pending.structure().name(), pending.attempts());
			CommunityStructures.cache().release(pending.structure());
			return;
		}

		if (QUEUED_CHUNKS.add(placementKey(pending.chunkPos(), pending.category()))) {
			PENDING.add(new PendingPlacement(pending.chunkPos(), pending.category(), pending.structure(), pending.seed(), pending.attempts() + 1));
		}
	}

	private static List<PlacementCandidate> placementCandidates(ChunkPos chunkPos, long worldSeed, CommunityStructureConfig config) {
		List<PlacementCandidate> candidates = new ArrayList<>(3);
		addSpreadCandidate(candidates, chunkPos, worldSeed, StructureCategory.LAND, config.landSpacingChunks, config.landSeparationChunks, config.landChancePerChunk, LAND_SALT);
		addSpreadCandidate(candidates, chunkPos, worldSeed, StructureCategory.WATER, config.waterSpacingChunks, config.waterSeparationChunks, config.waterChancePerChunk, WATER_SALT);
		addSpreadCandidate(candidates, chunkPos, worldSeed, StructureCategory.CAVE, config.caveSpacingChunks, config.caveSeparationChunks, config.caveChancePerChunk, CAVE_SALT);
		return candidates;
	}

	private static void addSpreadCandidate(List<PlacementCandidate> candidates, ChunkPos chunkPos, long worldSeed, StructureCategory category, int spacing, int separation, double frequency, int salt) {
		int spread = Math.max(1, spacing - separation);
		int regionX = Math.floorDiv(chunkPos.x, spacing);
		int regionZ = Math.floorDiv(chunkPos.z, spacing);
		long seed = spreadSeed(worldSeed, regionX, regionZ, salt);
		Random random = Random.create(seed);
		int candidateX = regionX * spacing + random.nextInt(spread);
		int candidateZ = regionZ * spacing + random.nextInt(spread);
		if (chunkPos.x != candidateX || chunkPos.z != candidateZ) {
			return;
		}
		if (random.nextDouble() >= frequency) {
			return;
		}
		candidates.add(new PlacementCandidate(category, seed ^ chunkSeed(chunkPos)));
	}

	private static PlacementOutcome place(ServerWorld world, PendingPlacement pending) {
		CommunityStructureConfig config = CommunityStructures.config();
		CachedStructure cached = pending.structure();
		Random random = Random.create(pending.seed());

		try {
			if (!world.isChunkLoaded(ChunkPos.toLong(pending.chunkPos().x, pending.chunkPos().z))) {
				logPlacementDebug(
					"Dropped stale {} community structure {} for unloaded chunk {}, {}",
					pending.category().apiName(),
					cached.name(),
					pending.chunkPos().x,
					pending.chunkPos().z
				);
				CommunityStructures.cache().release(cached);
				return PlacementOutcome.SKIPPED;
			}

			prepareAsync(cached, config.maxDownloadBytes);
			CompletableFuture<StructureSnapshot> snapshotFuture = SNAPSHOT_CACHE.get(cached.path());
			if (snapshotFuture == null || !snapshotFuture.isDone()) {
				return PlacementOutcome.RETRY;
			}
			StructureSnapshot snapshot = snapshotFuture.join();
			BlockRotation rotation = randomRotation(random);
			Vec3i size = rotatedSize(snapshot.size(), rotation);
			logPlacementDebug(
				"Attempting {} community structure {} for chunk {}, {} with rotated size {}",
				pending.category().apiName(),
				cached.name(),
				pending.chunkPos().x,
				pending.chunkPos().z,
				size
			);
			if (size.getX() > config.maxStructureWidth || size.getZ() > config.maxStructureWidth || size.getY() > config.maxStructureHeight) {
				CommunityStructures.LOGGER.debug("Skipping oversized community structure {} with size {}", cached.name(), size);
				CommunityStructures.cache().release(cached);
				return PlacementOutcome.SKIPPED;
			}

			BlockState[] palette = rotatedPalette(world, snapshot, rotation);
			PlacementPreset preset = cached.placementPreset();
			LandBlendPlan landBlendPlan = pending.category() == StructureCategory.LAND && config.landTerrainBlend && preset.shouldBlendLandTerrain()
				? analyzeLandBlend(snapshot, palette, rotation, size, config)
				: NO_LAND_BLEND;
			BlockPos footprintOrigin = centeredFootprintOrigin(pending.chunkPos(), size);
			if (!isFootprintLoaded(world, footprintOrigin, size)) {
				return PlacementOutcome.RETRY;
			}

			BlockPos origin;
			if (pending.category() == StructureCategory.LAND) {
				origin = landOrigin(world, footprintOrigin, size, config, preset, landBlendPlan.sinkDepth());
			} else if (pending.category() == StructureCategory.WATER) {
				origin = waterOrigin(world, footprintOrigin, size, preset);
			} else {
				origin = caveOrigin(world, footprintOrigin, random, size, config);
			}
			if (origin == null) {
				logPlacementDebug(
					"Skipped {} community structure {} near chunk {}, {} because no suitable origin was found",
					pending.category().apiName(),
					cached.name(),
					pending.chunkPos().x,
					pending.chunkPos().z
				);
				CommunityStructures.cache().release(cached);
				return PlacementOutcome.SKIPPED;
			}
			origin = applyFinalWorldOffset(origin, pending.category(), preset);

			if (snapshot.blockCount() >= config.progressivePlacementThresholdBlocks) {
				ACTIVE.add(new ActivePlacement(pending.category(), cached, pending.chunkPos(), origin, snapshot, rotation, size, palette, landBlendPlan, 0, 0));
				CommunityStructures.LOGGER.info(
					"Started gradual {} community structure {} at {} with {} blocks",
					cached.category().apiName(),
					cached.name(),
					origin.toShortString(),
					snapshot.blockCount()
				);
				return PlacementOutcome.PLACED;
			}

			int placedBlocks = placeSnapshotNow(world, pending.category(), origin, snapshot, palette, rotation, size);
			boolean placed = placedBlocks > 0;
			if (placed) {
				if (pending.category() == StructureCategory.LAND && config.landClearVegetation && preset.shouldClearLandVegetation()) {
					clearLandVegetation(world, origin, snapshot, palette, rotation, size, config.landVegetationClearanceMargin);
				}
				if (pending.category() == StructureCategory.LAND && config.landTerrainBlend && preset.shouldBlendLandTerrain()) {
					blendLandTerrain(world, origin, size, landBlendPlan, pending.seed(), config);
				}
				if (pending.category() == StructureCategory.LAND && config.landApplyBiomeSnow) {
					applyBiomeSnow(world, origin, size, landBlendPlan.skirtRadius());
				}
				CommunityStructures.cache().markUsed(cached);
				forgetPrepared(cached.path());
				rememberPlacement(pending.chunkPos());
				CommunityStructures.LOGGER.info("Placed {} community structure {} at {} with {} blocks", cached.category().apiName(), cached.name(), origin.toShortString(), placedBlocks);
			} else {
				CommunityStructures.cache().release(cached);
			}
			return placed ? PlacementOutcome.PLACED : PlacementOutcome.SKIPPED;
		} catch (Exception exception) {
			CommunityStructures.cache().release(cached);
			forgetPrepared(cached.path());
			CommunityStructures.LOGGER.warn("Could not place community structure {}", cached.path(), exception);
			return PlacementOutcome.SKIPPED;
		}
	}

	private static boolean processActivePlacement(ServerWorld world, CommunityStructureConfig config) {
		ActivePlacement active = ACTIVE.peek();
		if (active == null) {
			return false;
		}

		int placed = 0;
		int index = active.nextIndex();
		int scanned = active.scanned();
		while (placed < config.maxStructureBlocksPerTick && index < active.snapshot().blocks().size()) {
			NbtCompound blockNbt = active.snapshot().blocks().getCompound(index);
			PlacedBlock block = placedBlock(world, active.category(), active.origin(), blockNbt, active.palette(), active.rotatedSize(), active.rotation());
			if (block != null) {
				world.setBlockState(active.origin().add(block.offset()), block.state(), BULK_PLACE_FLAGS);
				placed++;
			}
			index++;
			scanned++;
		}

		if (index >= active.snapshot().blocks().size()) {
			ACTIVE.poll();
			PlacementPreset preset = active.structure().placementPreset();
			if (active.category() == StructureCategory.LAND && config.landClearVegetation && preset.shouldClearLandVegetation()) {
				clearLandVegetation(world, active.origin(), active.snapshot(), active.palette(), active.rotation(), active.rotatedSize(), config.landVegetationClearanceMargin);
			}
			if (active.category() == StructureCategory.LAND && config.landTerrainBlend && preset.shouldBlendLandTerrain()) {
				blendLandTerrain(world, active.origin(), active.rotatedSize(), active.landBlendPlan(), chunkSeed(active.chunkPos()), config);
			}
			if (active.category() == StructureCategory.LAND && config.landApplyBiomeSnow) {
				applyBiomeSnow(world, active.origin(), active.rotatedSize(), active.landBlendPlan().skirtRadius());
			}
			CommunityStructures.cache().markUsed(active.structure());
			forgetPrepared(active.structure().path());
			rememberPlacement(active.chunkPos());
			CommunityStructures.LOGGER.info(
				"Finished gradual {} community structure {} at {} with {} blocks",
				active.category().apiName(),
				active.structure().name(),
				active.origin().toShortString(),
				scanned
			);
			return !ACTIVE.isEmpty();
		} else {
			ACTIVE.poll();
			ACTIVE.add(new ActivePlacement(active.category(), active.structure(), active.chunkPos(), active.origin(), active.snapshot(), active.rotation(), active.rotatedSize(), active.palette(), active.landBlendPlan(), index, scanned));
			return true;
		}
	}

	private static void logPlacementDebug(String message, Object... arguments) {
		if (debugPlacementEvents < 80) {
			CommunityStructures.LOGGER.info(message, arguments);
			debugPlacementEvents++;
		} else {
			CommunityStructures.LOGGER.debug(message, arguments);
		}
	}

	private static StructureTemplate readTemplate(ServerWorld world, CachedStructure cached, int maxBytes) throws IOException {
		StructureTemplate cachedTemplate = TEMPLATE_CACHE.get(cached.path());
		if (cachedTemplate != null) {
			return cachedTemplate;
		}

		NbtCompound nbt = readStructureNbt(cached.path(), maxBytes);
		StructureTemplate template = new StructureTemplate();
		template.readNbt(world.createCommandRegistryWrapper(RegistryKeys.BLOCK), nbt);
		TEMPLATE_CACHE.put(cached.path(), template);
		return template;
	}

	private static StructureSnapshot readStructureSnapshot(CachedStructure cached, int maxBytes) throws IOException {
		NbtCompound nbt = readStructureNbt(cached.path(), maxBytes);
		if (nbt == null) {
			throw new IOException("Structure NBT could not be read");
		}
		NbtList sizeNbt = nbt.getList("size", NbtElement.INT_TYPE);
		if (sizeNbt.size() < 3) {
			throw new IOException("Structure is missing size tag");
		}
		return new StructureSnapshot(new Vec3i(sizeNbt.getInt(0), sizeNbt.getInt(1), sizeNbt.getInt(2)), nbt.getList("palette", NbtElement.COMPOUND_TYPE), nbt.getList("blocks", NbtElement.COMPOUND_TYPE));
	}

	private static BlockState[] rotatedPalette(ServerWorld world, StructureSnapshot snapshot, BlockRotation rotation) {
		NbtList paletteNbt = snapshot.palette();
		BlockState[] palette = new BlockState[paletteNbt.size()];
		for (int index = 0; index < paletteNbt.size(); index++) {
			palette[index] = NbtHelper.toBlockState(world.createCommandRegistryWrapper(RegistryKeys.BLOCK), paletteNbt.getCompound(index)).rotate(rotation);
		}
		return palette;
	}

	private static int placeSnapshotNow(ServerWorld world, StructureCategory category, BlockPos origin, StructureSnapshot snapshot, BlockState[] palette, BlockRotation rotation, Vec3i rotatedSize) {
		int placed = 0;
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			PlacedBlock block = placedBlock(world, category, origin, blockNbt, palette, rotatedSize, rotation);
			if (block != null) {
				world.setBlockState(origin.add(block.offset()), block.state(), BULK_PLACE_FLAGS);
				placed++;
			}
		}
		return placed;
	}

	private static int clearLandVegetation(ServerWorld world, BlockPos origin, StructureSnapshot snapshot, BlockState[] palette, BlockRotation rotation, Vec3i rotatedSize, int margin) {
		Set<Long> occupiedOffsets = occupiedStructureOffsets(snapshot, palette, rotation, rotatedSize);
		int minX = -margin;
		int maxX = rotatedSize.getX() + margin - 1;
		int minZ = -margin;
		int maxZ = rotatedSize.getZ() + margin - 1;
		int columns = (maxX - minX + 1) * (maxZ - minZ + 1);
		if (columns > 4096) {
			minX = 0;
			maxX = rotatedSize.getX() - 1;
			minZ = 0;
			maxZ = rotatedSize.getZ() - 1;
			columns = rotatedSize.getX() * rotatedSize.getZ();
			if (columns > 4096) {
				return 0;
			}
		}

		int minY = Math.max(world.getBottomY(), origin.getY());
		int maxY = Math.min(world.getBottomY() + world.getHeight() - 1, origin.getY() + rotatedSize.getY() + 10);
		int cleared = 0;
		for (int dx = minX; dx <= maxX; dx++) {
			for (int dz = minZ; dz <= maxZ; dz++) {
				for (int y = minY; y <= maxY; y++) {
					int dy = y - origin.getY();
					if (dx >= 0 && dx < rotatedSize.getX() && dz >= 0 && dz < rotatedSize.getZ() && dy >= 0 && dy < rotatedSize.getY() && occupiedOffsets.contains(blockOffsetKey(dx, dy, dz))) {
						continue;
					}

					BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
					if (shouldClearLandVegetation(world, pos, world.getBlockState(pos))) {
						world.setBlockState(pos, Blocks.AIR.getDefaultState(), BULK_PLACE_FLAGS);
						cleared++;
					}
				}
			}
		}

		if (cleared > 0) {
			CommunityStructures.LOGGER.info("Cleared {} existing vegetation blocks from land community structure footprint at {}", cleared, origin.toShortString());
		}
		return cleared;
	}

	private static Set<Long> occupiedStructureOffsets(StructureSnapshot snapshot, BlockState[] palette, BlockRotation rotation, Vec3i rotatedSize) {
		Set<Long> occupied = new HashSet<>();
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= palette.length || palette[stateIndex].isAir()) {
				continue;
			}
			Vec3i pos = blockPos(blockNbt);
			if (pos == null) {
				continue;
			}
			BlockPos offset = rotatedOffset(pos.getX(), pos.getY(), pos.getZ(), rotatedSize, rotation);
			occupied.add(blockOffsetKey(offset.getX(), offset.getY(), offset.getZ()));
		}
		return occupied;
	}

	private static PlacedBlock placedBlock(ServerWorld world, StructureCategory category, BlockPos origin, NbtCompound blockNbt, BlockState[] palette, Vec3i rotatedSize, BlockRotation rotation) {
		int stateIndex = blockNbt.getInt("state");
		if (stateIndex < 0 || stateIndex >= palette.length) {
			return null;
		}
		BlockState state = palette[stateIndex];
		Vec3i pos = blockPos(blockNbt);
		if (pos == null) {
			return null;
		}
		BlockPos offset = rotatedOffset(pos.getX(), pos.getY(), pos.getZ(), rotatedSize, rotation);
		state = processedState(world, category, origin.add(offset), state);
		if (state.isAir()) {
			return null;
		}
		return new PlacedBlock(offset, state);
	}

	private static Vec3i blockPos(NbtCompound blockNbt) {
		NbtList posList = blockNbt.getList("pos", NbtElement.INT_TYPE);
		if (posList.size() >= 3) {
			return new Vec3i(posList.getInt(0), posList.getInt(1), posList.getInt(2));
		}
		int[] posArray = blockNbt.getIntArray("pos");
		if (posArray.length >= 3) {
			return new Vec3i(posArray[0], posArray[1], posArray[2]);
		}
		return null;
	}

	private static BlockState processedState(ServerWorld world, StructureCategory category, BlockPos pos, BlockState state) {
		if (category == StructureCategory.WATER && pos.getY() < world.getSeaLevel()) {
			if (state.isAir()) {
				return Blocks.WATER.getDefaultState();
			}
			if (state.contains(Properties.WATERLOGGED)) {
				return state.with(Properties.WATERLOGGED, true);
			}
		}
		return state;
	}

	private static Vec3i rotatedSize(Vec3i size, BlockRotation rotation) {
		return switch (rotation) {
			case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> new Vec3i(size.getZ(), size.getY(), size.getX());
			default -> size;
		};
	}

	private static NbtCompound readStructureNbt(Path path, int maxBytes) throws IOException {
		try {
			return NbtIo.readCompressed(path, NbtSizeTracker.of(maxBytes));
		} catch (IOException compressedException) {
			return NbtIo.read(path);
		}
	}

	private static BlockPos rotatedOffset(int x, int y, int z, Vec3i rotatedSize, BlockRotation rotation) {
		return switch (rotation) {
			case CLOCKWISE_90 -> new BlockPos(rotatedSize.getX() - 1 - z, y, x);
			case CLOCKWISE_180 -> new BlockPos(rotatedSize.getX() - 1 - x, y, rotatedSize.getZ() - 1 - z);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, rotatedSize.getZ() - 1 - x);
			default -> new BlockPos(x, y, z);
		};
	}

	private static BlockPos centeredFootprintOrigin(ChunkPos chunkPos, Vec3i size) {
		return new BlockPos(chunkPos.getCenterX() - size.getX() / 2, 0, chunkPos.getCenterZ() - size.getZ() / 2);
	}

	private static BlockPos landOrigin(ServerWorld world, BlockPos footprintOrigin, Vec3i size, CommunityStructureConfig config, PlacementPreset preset, int sinkDepth) {
		List<Integer> heights = new ArrayList<>();
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		int totalSamples = 0;
		int drySamples = 0;

		BlockPos center = footprintOrigin.add(size.getX() / 2, 0, size.getZ() / 2);
		int centerY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
		BlockPos centerSurface = new BlockPos(center.getX(), centerY - 1, center.getZ());
		if (!isSolidGround(world, centerSurface)) {
			return null;
		}

		for (int dx : sampleOffsets(size.getX())) {
			for (int dz : sampleOffsets(size.getZ())) {
				totalSamples++;
				int x = footprintOrigin.getX() + dx;
				int z = footprintOrigin.getZ() + dz;
				int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				BlockPos surface = new BlockPos(x, y - 1, z);
				BlockState surfaceState = world.getBlockState(surface);
				if (surfaceState.isAir() || !world.getFluidState(surface).isEmpty() || !world.getFluidState(surface.up()).isEmpty()) {
					continue;
				}

				drySamples++;
				heights.add(y);
				minHeight = Math.min(minHeight, y);
				maxHeight = Math.max(maxHeight, y);
			}
		}

		int minimumDryPercent = size.getX() > 64 || size.getZ() > 64 ? 55 : 80;
		if (heights.isEmpty() || drySamples * 100 < totalSamples * minimumDryPercent || maxHeight - minHeight > preset.landSlopeLimit(config)) {
			return null;
		}

		heights.sort(Comparator.naturalOrder());
		int medianY = heights.get(heights.size() / 2);
		return new BlockPos(footprintOrigin.getX(), medianY - sinkDepth - preset.landExtraSink(), footprintOrigin.getZ());
	}

	private static BlockPos applyFinalWorldOffset(BlockPos origin, StructureCategory category, PlacementPreset preset) {
		return category == StructureCategory.LAND && preset == PlacementPreset.SURFACE_HOUSE ? origin.down() : origin;
	}

	private static BlockPos waterOrigin(ServerWorld world, BlockPos footprintOrigin, Vec3i size, PlacementPreset preset) {
		int totalSamples = 0;
		int wetSamples = 0;
		int seaLevel = world.getSeaLevel();
		List<Integer> floorHeights = new ArrayList<>();

		BlockPos center = footprintOrigin.add(size.getX() / 2, 0, size.getZ() / 2);
		if (!isWaterAt(world, new BlockPos(center.getX(), seaLevel - 1, center.getZ()))) {
			return null;
		}

		for (int dx : sampleOffsets(size.getX())) {
			for (int dz : sampleOffsets(size.getZ())) {
				totalSamples++;
				int x = footprintOrigin.getX() + dx;
				int z = footprintOrigin.getZ() + dz;
				if (isWaterAt(world, new BlockPos(x, seaLevel - 1, z))) {
					wetSamples++;
					floorHeights.add(world.getTopY(Heightmap.Type.OCEAN_FLOOR, x, z));
				}
			}
		}

		int minimumWetPercent = size.getX() > 64 || size.getZ() > 64 ? 60 : 75;
		if (totalSamples == 0 || wetSamples * 100 < totalSamples * minimumWetPercent) {
			return null;
		}

		if (preset == PlacementPreset.OCEAN_FLOOR && !floorHeights.isEmpty()) {
			floorHeights.sort(Comparator.naturalOrder());
			return new BlockPos(footprintOrigin.getX(), floorHeights.get(floorHeights.size() / 2), footprintOrigin.getZ());
		}
		return new BlockPos(footprintOrigin.getX(), seaLevel, footprintOrigin.getZ());
	}

	private static BlockPos caveOrigin(ServerWorld world, BlockPos footprintOrigin, Random random, Vec3i size, CommunityStructureConfig config) {
		int bottomY = world.getBottomY() + 4;
		int topY = world.getBottomY() + world.getHeight() - size.getY() - 4;
		int minY = Math.max(bottomY, config.minCaveY);
		int maxY = Math.min(topY, config.maxCaveY);
		if (minY > maxY) {
			return null;
		}

		for (int attempt = 0; attempt < 24; attempt++) {
			int y = minY + random.nextInt(maxY - minY + 1);
			BlockPos origin = new BlockPos(footprintOrigin.getX(), y, footprintOrigin.getZ());
			if (isGoodCavePocket(world, origin, size)) {
				return origin;
			}
		}

		return null;
	}

	private static boolean isGoodCavePocket(ServerWorld world, BlockPos origin, Vec3i size) {
		int centerX = origin.getX() + size.getX() / 2;
		int centerZ = origin.getZ() + size.getZ() / 2;
		int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
		if (origin.getY() + size.getY() > surfaceY - 8) {
			return false;
		}

		int solidFloor = 0;
		int airBody = 0;
		int total = 0;
		int minX = Math.max(0, size.getX() / 3);
		int maxX = Math.min(size.getX() - 1, size.getX() * 2 / 3);
		int minZ = Math.max(0, size.getZ() / 3);
		int maxZ = Math.min(size.getZ() - 1, size.getZ() * 2 / 3);

		for (int dx : sampleOffsetsBetween(minX, maxX)) {
			for (int dz : sampleOffsetsBetween(minZ, maxZ)) {
				BlockPos floor = origin.add(dx, -1, dz);
				BlockPos body = origin.add(dx, 0, dz);
				BlockPos head = origin.add(dx, 1, dz);
				total++;
				if (isSolidGround(world, floor)) {
					solidFloor++;
				}
				if (world.getBlockState(body).isAir() && world.getBlockState(head).isAir()) {
					airBody++;
				}
			}
		}

		return total > 0 && solidFloor * 3 >= total * 2 && airBody * 3 >= total * 2;
	}

	private static LandBlendPlan analyzeLandBlend(StructureSnapshot snapshot, BlockState[] palette, BlockRotation rotation, Vec3i rotatedSize, CommunityStructureConfig config) {
		Map<Long, LandColumn> columns = new HashMap<>();
		Set<Long> uploadedOffsets = new HashSet<>();
		Set<Long> terrainBlockingOffsets = new HashSet<>();
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= palette.length) {
				continue;
			}
			BlockState state = palette[stateIndex];
			if (state.isAir()) {
				continue;
			}

			Vec3i pos = blockPos(blockNbt);
			if (pos == null) {
				continue;
			}

			BlockPos offset = rotatedOffset(pos.getX(), pos.getY(), pos.getZ(), rotatedSize, rotation);
			uploadedOffsets.add(blockOffsetKey(offset.getX(), offset.getY(), offset.getZ()));
			if (!isTerrainBlendAnchorBlock(state)) {
				continue;
			}
			terrainBlockingOffsets.add(blockOffsetKey(offset.getX(), offset.getY(), offset.getZ()));

			long key = columnKey(offset.getX(), offset.getZ());
			LandColumn existing = columns.get(key);
			boolean terrain = isTerrainSurfaceBlock(state);
			int topTerrainY = terrain && offset.getY() <= LAND_BASE_SCAN_HEIGHT ? offset.getY() : Integer.MIN_VALUE;
			if (existing == null) {
				columns.put(key, new LandColumn(offset.getX(), offset.getZ(), offset.getY(), state, topTerrainY));
				continue;
			}

			int bottomY = existing.bottomY();
			BlockState bottomState = existing.bottomState();
			if (offset.getY() < bottomY) {
				bottomY = offset.getY();
				bottomState = state;
			}
			int highestTerrainY = Math.max(existing.topTerrainY(), topTerrainY);
			columns.put(key, new LandColumn(existing.x(), existing.z(), bottomY, bottomState, highestTerrainY));
		}

		if (columns.isEmpty()) {
			return NO_LAND_BLEND;
		}

		int terrainBottomColumns = 0;
		int terrainTopColumns = 0;
		for (LandColumn column : columns.values()) {
			if (isTerrainLikeBlock(column.bottomState())) {
				terrainBottomColumns++;
			}
			if (column.topTerrainY() != Integer.MIN_VALUE) {
				terrainTopColumns++;
			}
		}

		int footprintArea = Math.max(1, rotatedSize.getX() * rotatedSize.getZ());
		double bottomCoverage = columns.size() / (double) footprintArea;
		double terrainBottomRatio = terrainBottomColumns / (double) columns.size();
		double terrainTopRatio = terrainTopColumns / (double) columns.size();
		boolean authoredTerrainBase = bottomCoverage >= 0.45D && terrainBottomRatio >= 0.45D && terrainTopRatio >= 0.35D;
		boolean sparseSupports = bottomCoverage < 0.18D;

		int sinkDepth = 0;
		if (authoredTerrainBase) {
			sinkDepth = Math.min(config.landMaxSinkDepth, rotatedSize.getY() >= 36 ? 4 : 3);
		} else if (!sparseSupports && terrainBottomRatio >= 0.30D) {
			sinkDepth = Math.min(config.landMaxSinkDepth, 2);
		} else if (!sparseSupports && bottomCoverage >= 0.35D) {
			sinkDepth = Math.min(config.landMaxSinkDepth, 1);
		}

		int skirtRadius = authoredTerrainBase ? config.landTerrainBlendRadius : Math.min(config.landTerrainBlendRadius, 3);
		return new LandBlendPlan(sinkDepth, skirtRadius, columns, uploadedOffsets, terrainBlockingOffsets);
	}

	private static void blendLandTerrain(ServerWorld world, BlockPos origin, Vec3i rotatedSize, LandBlendPlan plan, long seed, CommunityStructureConfig config) {
		if (plan.columns().isEmpty()) {
			return;
		}

		int placed = 0;
		placed += addNaturalSupports(world, origin, plan, config.landFoundationDepth + plan.sinkDepth());
		if (plan.skirtRadius() > 0) {
			placed += addInteriorTerrainFill(world, origin, rotatedSize, plan, seed);
			placed += addTerrainSkirt(world, origin, rotatedSize, plan, seed);
			placed += enforceTerrainPriority(world, origin, rotatedSize, plan, seed);
			placed += patchLowerTerrainHoles(world, origin, rotatedSize, plan);
		}
		if (placed > 0) {
			CommunityStructures.LOGGER.info("Blended land community structure terrain at {} with {} terrain blocks", origin.toShortString(), placed);
		}
	}

	private static int addNaturalSupports(ServerWorld world, BlockPos origin, LandBlendPlan plan, int maxDepth) {
		if (maxDepth <= 0) {
			return 0;
		}

		int placed = 0;
		for (LandColumn column : plan.columns().values()) {
			BlockState supportState = supportTerrainState(column.bottomState());
			for (int depth = 1; depth <= maxDepth; depth++) {
				BlockPos pos = origin.add(column.x(), column.bottomY() - depth, column.z());
				if (isSolidGround(world, pos)) {
					break;
				}
				if (!canReplaceWithTerrainBlend(world, pos)) {
					break;
				}
				world.setBlockState(pos, supportState, BULK_PLACE_FLAGS);
				placed++;
			}
		}
		return placed;
	}

	private static int addInteriorTerrainFill(ServerWorld world, BlockPos origin, Vec3i rotatedSize, LandBlendPlan plan, long seed) {
		int placed = 0;
		for (int dx = 0; dx < rotatedSize.getX(); dx++) {
			for (int dz = 0; dz < rotatedSize.getZ(); dz++) {
				EdgeTerrain interiorTerrain = smoothedInteriorTerrain(origin, plan, dx, dz);
				if (interiorTerrain == null) {
					continue;
				}

				int targetY = interiorTerrain.worldY() - 1 - columnNoise(seed ^ 0x31d2a1b5c7e34f91L, origin.getX() + dx, origin.getZ() + dz, 2);
				int minY = Math.max(world.getBottomY(), origin.getY() - plan.sinkDepth() - 1);
				int maxY = Math.min(targetY, origin.getY() + Math.min(rotatedSize.getY() - 1, LAND_BASE_SCAN_HEIGHT));
				for (int y = minY; y <= maxY; y++) {
					int localY = y - origin.getY();
					long offsetKey = blockOffsetKey(dx, localY, dz);
					if (plan.terrainBlockingOffsets().contains(offsetKey)) {
						continue;
					}
					BlockPos pos = new BlockPos(origin.getX() + dx, y, origin.getZ() + dz);
					if (!canReplaceWithInteriorTerrainBlend(world, pos)) {
						continue;
					}
					int depth = Math.max(0, targetY - y);
					BlockState localSurface = localSurfaceState(world, pos.getX(), pos.getZ(), Math.max(world.getBottomY(), surfaceY(world, pos.getX(), pos.getZ())));
					world.setBlockState(pos, skirtTerrainState(localSurface, interiorTerrain.sourceState(), depth, Math.max(1, targetY - minY)), BULK_PLACE_FLAGS);
					placed++;
				}
			}
		}
		return placed;
	}

	private static int addTerrainSkirt(ServerWorld world, BlockPos origin, Vec3i rotatedSize, LandBlendPlan plan, long seed) {
		int radius = plan.skirtRadius();
		int placed = 0;
		for (int dx = -radius; dx < rotatedSize.getX() + radius; dx++) {
			for (int dz = -radius; dz < rotatedSize.getZ() + radius; dz++) {
				if (dx >= 0 && dx < rotatedSize.getX() && dz >= 0 && dz < rotatedSize.getZ()) {
					continue;
				}

				int distance = distanceOutsideFootprint(dx, dz, rotatedSize);
				if (distance <= 0 || distance > radius) {
					continue;
				}

				int edgeX = clamp(dx, 0, rotatedSize.getX() - 1);
				int edgeZ = clamp(dz, 0, rotatedSize.getZ() - 1);
				EdgeTerrain edgeTerrain = smoothedEdgeTerrain(origin, plan, edgeX, edgeZ);
				if (edgeTerrain == null) {
					continue;
				}

				int worldX = origin.getX() + dx;
				int worldZ = origin.getZ() + dz;
				int existingSurfaceY = surfaceY(world, worldX, worldZ);
				if (existingSurfaceY <= world.getBottomY()) {
					continue;
				}

				int edgeTerrainY = edgeTerrain.worldY();
				int heightDelta = edgeTerrainY - existingSurfaceY;
				if (heightDelta <= 1) {
					continue;
				}

				double blend = smoothBlendWeight(distance, radius);
				int noise = columnNoise(seed ^ 0x6eed0e9da4d94a4fL, worldX, worldZ, 3) - 1;
				int targetY = existingSurfaceY + (int) Math.round(heightDelta * blend) + noise;
				targetY = Math.min(targetY, edgeTerrainY - Math.max(0, distance - 1));
				targetY = Math.min(targetY, existingSurfaceY + Math.max(5, radius * 2 + plan.sinkDepth()));
				if (targetY <= existingSurfaceY) {
					continue;
				}

				BlockState localSurface = localSurfaceState(world, worldX, worldZ, existingSurfaceY);
				int fillHeight = targetY - existingSurfaceY;
				for (int y = existingSurfaceY + 1; y <= targetY; y++) {
					BlockPos pos = new BlockPos(worldX, y, worldZ);
					if (!canReplaceWithHardTerrainBlend(world, pos)) {
						break;
					}
					world.setBlockState(pos, skirtTerrainState(localSurface, edgeTerrain.sourceState(), targetY - y, fillHeight), BULK_PLACE_FLAGS);
					placed++;
				}
			}
		}
		return placed;
	}

	private static int enforceTerrainPriority(ServerWorld world, BlockPos origin, Vec3i rotatedSize, LandBlendPlan plan, long seed) {
		int changed = 0;
		for (int dx = 0; dx < rotatedSize.getX(); dx++) {
			for (int dz = 0; dz < rotatedSize.getZ(); dz++) {
				EdgeTerrain interiorTerrain = smoothedInteriorTerrain(origin, plan, dx, dz);
				if (interiorTerrain == null) {
					continue;
				}

				int worldX = origin.getX() + dx;
				int worldZ = origin.getZ() + dz;
				int targetY = interiorTerrain.worldY() - columnNoise(seed ^ 0x45d9f3b17a2c93ffL, worldX, worldZ, 2);
				int minY = Math.max(world.getBottomY(), origin.getY() - plan.sinkDepth() - 1);
				int maxY = Math.min(targetY + 2, origin.getY() + Math.min(rotatedSize.getY() - 1, LAND_BASE_SCAN_HEIGHT + 4));
				BlockState localSurface = localSurfaceState(world, worldX, worldZ, Math.max(world.getBottomY(), surfaceY(world, worldX, worldZ)));
				for (int y = minY; y <= maxY; y++) {
					int localY = y - origin.getY();
					long offsetKey = blockOffsetKey(dx, localY, dz);
					if (plan.terrainBlockingOffsets().contains(offsetKey)) {
						continue;
					}

					BlockPos pos = new BlockPos(worldX, y, worldZ);
					BlockState state = world.getBlockState(pos);
					if (!canReplaceWithPriorityTerrain(world, pos, state)) {
						continue;
					}

					if (y <= targetY) {
						world.setBlockState(pos, skirtTerrainState(localSurface, interiorTerrain.sourceState(), targetY - y, Math.max(1, targetY - minY)), BULK_PLACE_FLAGS);
					} else if (isUploadedSoftDecorationBlock(state) || state.getCollisionShape(world, pos).isEmpty() || !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
						world.setBlockState(pos, Blocks.AIR.getDefaultState(), BULK_PLACE_FLAGS);
					}
					changed++;
				}
			}
		}
		return changed;
	}

	private static int patchLowerTerrainHoles(ServerWorld world, BlockPos origin, Vec3i rotatedSize, LandBlendPlan plan) {
		int changed = 0;
		int minY = Math.max(world.getBottomY(), origin.getY() - plan.sinkDepth() - 1);
		int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, origin.getY() + Math.min(rotatedSize.getY() - 1, LAND_BASE_SCAN_HEIGHT + 4));
		for (int dx = 0; dx < rotatedSize.getX(); dx++) {
			for (int dz = 0; dz < rotatedSize.getZ(); dz++) {
				int worldX = origin.getX() + dx;
				int worldZ = origin.getZ() + dz;
				TerrainSurface nearbySurface = nearbyTerrainHoleSurface(world, origin, rotatedSize, dx, dz, minY, maxY);
				if (nearbySurface == null) {
					continue;
				}

				int targetY = nearbySurface.worldY();
				int existingSurfaceY = terrainSurfaceYInColumn(world, worldX, worldZ, minY, maxY);
				if (existingSurfaceY >= targetY - 1) {
					changed += clearSoftDecorationAboveTerrain(world, worldX, worldZ, existingSurfaceY, maxY);
					continue;
				}

				BlockState localSurface = localSurfaceState(world, worldX, worldZ, Math.max(world.getBottomY(), targetY));
				for (int y = minY; y <= targetY; y++) {
					int localY = y - origin.getY();
					long offsetKey = blockOffsetKey(dx, localY, dz);
					if (plan.terrainBlockingOffsets().contains(offsetKey)) {
						continue;
					}

					BlockPos pos = new BlockPos(worldX, y, worldZ);
					BlockState state = world.getBlockState(pos);
					if (!canReplaceWithPriorityTerrain(world, pos, state)) {
						continue;
					}

					world.setBlockState(pos, skirtTerrainState(localSurface, nearbySurface.state(), targetY - y, Math.max(1, targetY - minY)), BULK_PLACE_FLAGS);
					changed++;
				}
				changed += clearSoftDecorationAboveTerrain(world, worldX, worldZ, targetY, maxY);
			}
		}
		return changed;
	}

	private static TerrainSurface nearbyTerrainHoleSurface(ServerWorld world, BlockPos origin, Vec3i rotatedSize, int x, int z, int minY, int maxY) {
		double totalY = 0.0D;
		double totalWeight = 0.0D;
		BlockState sourceState = null;
		int nearestDistance = Integer.MAX_VALUE;
		int radius = Math.min(5, Math.max(3, rotatedSize.getX() / 16 + rotatedSize.getZ() / 16));
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int distance = Math.max(Math.abs(dx), Math.abs(dz));
				if (distance == 0 || distance > radius) {
					continue;
				}

				int worldX = origin.getX() + x + dx;
				int worldZ = origin.getZ() + z + dz;
				if (!isColumnLoaded(world, worldX, worldZ)) {
					continue;
				}

				TerrainSurface surface = terrainSurfaceInColumn(world, worldX, worldZ, minY, maxY);
				if (surface == null) {
					continue;
				}

				double weight = 1.0D / (distance + 1.0D);
				totalY += surface.worldY() * weight;
				totalWeight += weight;
				if (distance < nearestDistance || sourceState == null || isStoneTerrainBlock(surface.state())) {
					nearestDistance = distance;
					sourceState = surface.state();
				}
			}
		}

		if (totalWeight <= 0.0D) {
			return null;
		}
		return new TerrainSurface((int) Math.round(totalY / totalWeight), sourceState == null ? Blocks.DIRT.getDefaultState() : sourceState);
	}

	private static TerrainSurface terrainSurfaceInColumn(ServerWorld world, int x, int z, int minY, int maxY) {
		for (int y = maxY; y >= minY; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = world.getBlockState(pos);
			if (!isTerrainSurfaceBlock(state)) {
				continue;
			}
			BlockState above = world.getBlockState(pos.up());
			if (above.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) && !isUploadedSoftDecorationBlock(above)) {
				continue;
			}
			return new TerrainSurface(y, state);
		}
		return null;
	}

	private static int terrainSurfaceYInColumn(ServerWorld world, int x, int z, int minY, int maxY) {
		TerrainSurface surface = terrainSurfaceInColumn(world, x, z, minY, maxY);
		return surface == null ? Integer.MIN_VALUE : surface.worldY();
	}

	private static int clearSoftDecorationAboveTerrain(ServerWorld world, int x, int z, int terrainY, int maxY) {
		int changed = 0;
		int top = Math.min(maxY, terrainY + 2);
		for (int y = terrainY + 1; y <= top; y++) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState state = world.getBlockState(pos);
			if (state.isAir() || !canReplaceWithPriorityTerrain(world, pos, state)) {
				continue;
			}
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), BULK_PLACE_FLAGS);
			changed++;
		}
		return changed;
	}

	private static EdgeTerrain smoothedEdgeTerrain(BlockPos origin, LandBlendPlan plan, int edgeX, int edgeZ) {
		int totalY = 0;
		int count = 0;
		BlockState sourceState = null;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				LandColumn column = plan.columns().get(columnKey(edgeX + dx, edgeZ + dz));
				if (column == null || column.topTerrainY() == Integer.MIN_VALUE) {
					continue;
				}
				totalY += column.topTerrainY();
				count++;
				if (sourceState == null || isStoneTerrainBlock(column.bottomState())) {
					sourceState = column.bottomState();
				}
			}
		}
		if (count == 0) {
			return null;
		}
		return new EdgeTerrain(origin.getY() + Math.round((float) totalY / count), sourceState == null ? Blocks.DIRT.getDefaultState() : sourceState);
	}

	private static EdgeTerrain smoothedInteriorTerrain(BlockPos origin, LandBlendPlan plan, int x, int z) {
		double totalY = 0.0D;
		double totalWeight = 0.0D;
		BlockState sourceState = null;
		int nearestDistance = Integer.MAX_VALUE;
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				int distance = Math.abs(dx) + Math.abs(dz);
				if (distance == 0 || distance > 12) {
					continue;
				}
				LandColumn column = plan.columns().get(columnKey(x + dx, z + dz));
				if (column == null || column.topTerrainY() == Integer.MIN_VALUE) {
					continue;
				}
				double weight = 1.0D / (distance + 1.0D);
				totalY += column.topTerrainY() * weight;
				totalWeight += weight;
				if (distance < nearestDistance || sourceState == null || isStoneTerrainBlock(column.bottomState())) {
					nearestDistance = distance;
					sourceState = column.bottomState();
				}
			}
		}
		if (totalWeight <= 0.0D || nearestDistance > 8) {
			return null;
		}
		int localY = (int) Math.floor(totalY / totalWeight) - Math.max(0, nearestDistance - 1);
		if (localY < 0) {
			return null;
		}
		return new EdgeTerrain(origin.getY() + localY, sourceState == null ? Blocks.DIRT.getDefaultState() : sourceState);
	}

	private static int applyBiomeSnow(ServerWorld world, BlockPos origin, Vec3i rotatedSize, int skirtRadius) {
		int radius = Math.min(16, Math.max(0, skirtRadius));
		int minX = origin.getX() - radius;
		int maxX = origin.getX() + rotatedSize.getX() + radius - 1;
		int minZ = origin.getZ() - radius;
		int maxZ = origin.getZ() + rotatedSize.getZ() + radius - 1;
		int columns = (maxX - minX + 1) * (maxZ - minZ + 1);
		boolean snowContext = areaLooksSnowy(world, minX, maxX, minZ, maxZ, origin.getY(), rotatedSize.getY());
		if (columns > 50000 || !snowContext) {
			return 0;
		}

		int minY = Math.max(world.getBottomY(), origin.getY() - 4);
		int maxY = Math.min(world.getBottomY() + world.getHeight() - 2, origin.getY() + rotatedSize.getY() + 18);
		int placed = 0;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int topY = Math.min(maxY, surfaceY(world, x, z));
				if (topY < minY) {
					continue;
				}

				BlockPos supportPos = exposedSnowSupport(world, x, topY, z, minY);
				if (supportPos == null) {
					continue;
				}

				BlockState supportState = world.getBlockState(supportPos);
				BlockPos snowPos = supportPos.up();
				if (canPlaceBiomeSnow(world, snowPos, supportState, snowContext)) {
					world.setBlockState(snowPos, Blocks.SNOW.getDefaultState(), BULK_PLACE_FLAGS);
					placed++;
				}
			}
		}

		if (placed > 0) {
			CommunityStructures.LOGGER.info("Applied {} snow layers to land community structure at {}", placed, origin.toShortString());
		}
		return placed;
	}

	private static boolean areaLooksSnowy(ServerWorld world, int minX, int maxX, int minZ, int maxZ, int originY, int height) {
		int stepX = Math.max(1, (maxX - minX) / 4);
		int stepZ = Math.max(1, (maxZ - minZ) / 4);
		int sampleY = Math.min(world.getBottomY() + world.getHeight() - 2, originY + Math.max(1, height / 2));
		for (int x = minX; x <= maxX; x += stepX) {
			for (int z = minZ; z <= maxZ; z += stepZ) {
				BlockPos pos = new BlockPos(x, sampleY, z);
				if (world.getBiome(pos).value().canSetSnow(world, pos)) {
					return true;
				}
				BlockPos surface = new BlockPos(x, surfaceY(world, x, z), z);
				BlockState surfaceState = world.getBlockState(surface);
				if (surfaceState.isOf(Blocks.SNOW) || surfaceState.isOf(Blocks.SNOW_BLOCK) || world.getBlockState(surface.up()).isOf(Blocks.SNOW)) {
					return true;
				}
			}
		}
		return false;
	}

	private static BlockPos exposedSnowSupport(ServerWorld world, int x, int topY, int z, int minY) {
		BlockPos top = new BlockPos(x, topY, z);
		BlockState topState = world.getBlockState(top);
		if (topState.isOf(Blocks.SNOW)) {
			return null;
		}
		if (isUploadedSoftDecorationBlock(topState) || topState.getCollisionShape(world, top).isEmpty()) {
			for (int y = topY; y >= minY; y--) {
				BlockPos pos = new BlockPos(x, y, z);
				BlockState state = world.getBlockState(pos);
				if (state.isAir() || state.isOf(Blocks.SNOW) || !world.getFluidState(pos).isEmpty()) {
					continue;
				}
				if (isUploadedSoftDecorationBlock(state) || state.getCollisionShape(world, pos).isEmpty()) {
					world.setBlockState(pos, Blocks.AIR.getDefaultState(), BULK_PLACE_FLAGS);
					continue;
				}
				return pos;
			}
			return null;
		}
		return top;
	}

	private static boolean canPlaceBiomeSnow(ServerWorld world, BlockPos snowPos, BlockState supportState, boolean snowContext) {
		if (!world.getBlockState(snowPos).isAir() || !world.getFluidState(snowPos).isEmpty()) {
			return false;
		}
		if (!supportState.isSideSolidFullSquare(world, snowPos.down(), Direction.UP)) {
			return false;
		}
		BlockState snowState = Blocks.SNOW.getDefaultState();
		return snowState.canPlaceAt(world, snowPos) && (snowContext || world.getBiome(snowPos).value().canSetSnow(world, snowPos));
	}

	private static double smoothBlendWeight(int distance, int radius) {
		double t = 1.0D - Math.max(0.0D, Math.min(1.0D, (distance - 0.5D) / (radius + 0.5D)));
		return t * t * (3.0D - 2.0D * t);
	}

	private static int distanceOutsideFootprint(int x, int z, Vec3i size) {
		int dx = x < 0 ? -x : x >= size.getX() ? x - size.getX() + 1 : 0;
		int dz = z < 0 ? -z : z >= size.getZ() ? z - size.getZ() + 1 : 0;
		return Math.max(dx, dz);
	}

	private static int surfaceY(ServerWorld world, int x, int z) {
		return world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
	}

	private static BlockState localSurfaceState(ServerWorld world, int x, int z, int surfaceY) {
		BlockPos surface = new BlockPos(x, surfaceY, z);
		BlockState state = world.getBlockState(surface);
		if (state.isOf(Blocks.SNOW)) {
			BlockState below = world.getBlockState(surface.down());
			if (below.isOf(Blocks.SNOW_BLOCK)) {
				return Blocks.SNOW_BLOCK.getDefaultState();
			}
			if (below.isOf(Blocks.GRASS_BLOCK) || below.isOf(Blocks.DIRT) || below.isOf(Blocks.PODZOL)) {
				return Blocks.GRASS_BLOCK.getDefaultState();
			}
		}
		return isTerrainLikeBlock(state) ? state : Blocks.GRASS_BLOCK.getDefaultState();
	}

	private static BlockState skirtTerrainState(BlockState localSurface, BlockState sourceState, int depthBelowTop, int fillHeight) {
		if (localSurface.isOf(Blocks.SNOW_BLOCK)) {
			return depthBelowTop <= Math.min(4, Math.max(2, fillHeight / 2)) ? Blocks.SNOW_BLOCK.getDefaultState() : Blocks.STONE.getDefaultState();
		}
		if (depthBelowTop <= 0) {
			return surfaceTerrainState(localSurface);
		}
		if (depthBelowTop <= 3) {
			return shallowTerrainState(localSurface);
		}
		return deepTerrainState(localSurface, sourceState);
	}

	private static BlockState surfaceTerrainState(BlockState localSurface) {
		if (localSurface.isOf(Blocks.GRASS_BLOCK) || localSurface.isOf(Blocks.PODZOL) || localSurface.isOf(Blocks.MYCELIUM) || localSurface.isOf(Blocks.MOSS_BLOCK) || localSurface.isOf(Blocks.DIRT_PATH)) {
			return localSurface;
		}
		if (localSurface.isOf(Blocks.SAND) || localSurface.isOf(Blocks.RED_SAND) || localSurface.isOf(Blocks.GRAVEL) || localSurface.isOf(Blocks.MUD) || localSurface.isOf(Blocks.CLAY) || localSurface.isOf(Blocks.SNOW_BLOCK)) {
			return localSurface;
		}
		return Blocks.GRASS_BLOCK.getDefaultState();
	}

	private static BlockState shallowTerrainState(BlockState localSurface) {
		if (localSurface.isOf(Blocks.SAND) || localSurface.isOf(Blocks.RED_SAND) || localSurface.isOf(Blocks.GRAVEL) || localSurface.isOf(Blocks.MUD) || localSurface.isOf(Blocks.CLAY)) {
			return localSurface;
		}
		if (localSurface.isOf(Blocks.FARMLAND) || localSurface.isOf(Blocks.DIRT_PATH)) {
			return Blocks.DIRT.getDefaultState();
		}
		if (localSurface.isOf(Blocks.MOSS_BLOCK)) {
			return Blocks.DIRT.getDefaultState();
		}
		return Blocks.DIRT.getDefaultState();
	}

	private static BlockState deepTerrainState(BlockState localSurface, BlockState sourceState) {
		if (localSurface.isOf(Blocks.SAND)) {
			return Blocks.SANDSTONE.getDefaultState();
		}
		if (localSurface.isOf(Blocks.RED_SAND)) {
			return Blocks.RED_SANDSTONE.getDefaultState();
		}
		if (localSurface.isOf(Blocks.MUD)) {
			return Blocks.PACKED_MUD.getDefaultState();
		}
		if (isStoneTerrainBlock(sourceState)) {
			return normalizedStoneState(sourceState);
		}
		return Blocks.STONE.getDefaultState();
	}

	private static BlockState supportTerrainState(BlockState sourceState) {
		if (sourceState.isOf(Blocks.SAND) || sourceState.isOf(Blocks.RED_SAND) || sourceState.isOf(Blocks.GRAVEL) || sourceState.isOf(Blocks.MUD) || sourceState.isOf(Blocks.CLAY)) {
			return sourceState;
		}
		if (isStoneTerrainBlock(sourceState)) {
			return normalizedStoneState(sourceState);
		}
		return Blocks.DIRT.getDefaultState();
	}

	private static BlockState normalizedStoneState(BlockState sourceState) {
		if (sourceState.isOf(Blocks.ANDESITE) || sourceState.isOf(Blocks.DIORITE) || sourceState.isOf(Blocks.GRANITE) || sourceState.isOf(Blocks.TUFF) || sourceState.isOf(Blocks.DEEPSLATE) || sourceState.isOf(Blocks.COBBLED_DEEPSLATE) || sourceState.isOf(Blocks.COBBLESTONE)) {
			return sourceState.getBlock().getDefaultState();
		}
		return Blocks.STONE.getDefaultState();
	}

	private static boolean canReplaceWithTerrainBlend(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.isAir() || !world.getFluidState(pos).isEmpty() || state.getCollisionShape(world, pos).isEmpty();
	}

	private static boolean canReplaceWithInteriorTerrainBlend(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return canReplaceWithTerrainBlend(world, pos) || !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static boolean canReplaceWithHardTerrainBlend(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return canReplaceWithTerrainBlend(world, pos) || !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static boolean canReplaceWithPriorityTerrain(ServerWorld world, BlockPos pos, BlockState state) {
		return state.isAir()
			|| !world.getFluidState(pos).isEmpty()
			|| isUploadedSoftDecorationBlock(state)
			|| state.getCollisionShape(world, pos).isEmpty()
			|| !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static boolean isTerrainBlendAnchorBlock(BlockState state) {
		return isTerrainSurfaceBlock(state)
			|| (!isUploadedSoftDecorationBlock(state) && state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN));
	}

	private static boolean isTerrainSurfaceBlock(BlockState state) {
		return isTerrainLikeBlock(state)
			|| state.isOf(Blocks.FARMLAND)
			|| state.isOf(Blocks.DIRT_PATH);
	}

	private static boolean isUploadedSoftDecorationBlock(BlockState state) {
		return state.isIn(BlockTags.LEAVES)
			|| state.isIn(BlockTags.SAPLINGS)
			|| state.isIn(BlockTags.FLOWERS)
			|| state.isIn(BlockTags.CROPS)
			|| state.isOf(Blocks.SHORT_GRASS)
			|| state.isOf(Blocks.TALL_GRASS)
			|| state.isOf(Blocks.FERN)
			|| state.isOf(Blocks.LARGE_FERN)
			|| state.isOf(Blocks.VINE)
			|| state.isOf(Blocks.WEEPING_VINES)
			|| state.isOf(Blocks.WEEPING_VINES_PLANT)
			|| state.isOf(Blocks.TWISTING_VINES)
			|| state.isOf(Blocks.TWISTING_VINES_PLANT)
			|| state.isOf(Blocks.CAVE_VINES)
			|| state.isOf(Blocks.CAVE_VINES_PLANT)
			|| !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static boolean shouldClearLandVegetation(ServerWorld world, BlockPos pos, BlockState state) {
		if (state.isOf(Blocks.SNOW) || state.isOf(Blocks.SNOW_BLOCK) || state.isOf(Blocks.POWDER_SNOW)) {
			return false;
		}
		return state.isIn(BlockTags.LOGS)
			|| state.isIn(BlockTags.LEAVES)
			|| state.isIn(BlockTags.SAPLINGS)
			|| state.isIn(BlockTags.FLOWERS)
			|| state.isIn(BlockTags.CROPS)
			|| state.isIn(BlockTags.REPLACEABLE_BY_TREES)
			|| state.isOf(Blocks.SHORT_GRASS)
			|| state.isOf(Blocks.TALL_GRASS)
			|| state.isOf(Blocks.FERN)
			|| state.isOf(Blocks.LARGE_FERN)
			|| state.isOf(Blocks.VINE)
			|| state.isOf(Blocks.WEEPING_VINES)
			|| state.isOf(Blocks.WEEPING_VINES_PLANT)
			|| state.isOf(Blocks.TWISTING_VINES)
			|| state.isOf(Blocks.TWISTING_VINES_PLANT)
			|| state.isOf(Blocks.CAVE_VINES)
			|| state.isOf(Blocks.CAVE_VINES_PLANT)
			|| state.isOf(Blocks.DEAD_BUSH)
			|| state.isOf(Blocks.BAMBOO)
			|| state.isOf(Blocks.BAMBOO_SAPLING)
			|| state.getCollisionShape(world, pos).isEmpty() && !state.isAir() && world.getFluidState(pos).isEmpty();
	}

	private static boolean isTerrainLikeBlock(BlockState state) {
		return state.isOf(Blocks.GRASS_BLOCK)
			|| state.isOf(Blocks.DIRT)
			|| state.isOf(Blocks.COARSE_DIRT)
			|| state.isOf(Blocks.ROOTED_DIRT)
			|| state.isOf(Blocks.PODZOL)
			|| state.isOf(Blocks.MYCELIUM)
			|| state.isOf(Blocks.DIRT_PATH)
			|| state.isOf(Blocks.FARMLAND)
			|| state.isOf(Blocks.MOSS_BLOCK)
			|| state.isOf(Blocks.MUD)
			|| state.isOf(Blocks.PACKED_MUD)
			|| state.isOf(Blocks.CLAY)
			|| state.isOf(Blocks.GRAVEL)
			|| state.isOf(Blocks.SAND)
			|| state.isOf(Blocks.RED_SAND)
			|| state.isOf(Blocks.SANDSTONE)
			|| state.isOf(Blocks.RED_SANDSTONE)
			|| state.isOf(Blocks.SNOW_BLOCK)
			|| isStoneTerrainBlock(state);
	}

	private static boolean isStoneTerrainBlock(BlockState state) {
		return state.isOf(Blocks.STONE)
			|| state.isOf(Blocks.ANDESITE)
			|| state.isOf(Blocks.DIORITE)
			|| state.isOf(Blocks.GRANITE)
			|| state.isOf(Blocks.TUFF)
			|| state.isOf(Blocks.CALCITE)
			|| state.isOf(Blocks.DEEPSLATE)
			|| state.isOf(Blocks.COBBLED_DEEPSLATE)
			|| state.isOf(Blocks.COBBLESTONE);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int columnNoise(long seed, int x, int z, int bound) {
		if (bound <= 0) {
			return 0;
		}
		long mixed = seed;
		mixed ^= (long) x * 0xbf58476d1ce4e5b9L;
		mixed ^= (long) z * 0x94d049bb133111ebL;
		mixed ^= mixed >>> 30;
		mixed *= 0xbf58476d1ce4e5b9L;
		mixed ^= mixed >>> 27;
		return (int) Math.floorMod(mixed, bound);
	}

	private static long columnKey(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private static long blockOffsetKey(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static boolean isSolidGround(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return !state.isAir() && world.getFluidState(pos).isEmpty();
	}

	private static boolean isWaterAt(ServerWorld world, BlockPos pos) {
		return !world.getFluidState(pos).isEmpty() && world.getBlockState(pos).isOf(Blocks.WATER);
	}

	private static List<Integer> sampleOffsets(int size) {
		return sampleOffsetsBetween(0, size - 1);
	}

	private static List<Integer> sampleOffsetsBetween(int min, int max) {
		List<Integer> offsets = new ArrayList<>();
		int size = max - min + 1;
		int step = size > 128 ? 12 : size > 64 ? 8 : TERRAIN_SAMPLE_STEP;
		for (int offset = min; offset <= max; offset += step) {
			offsets.add(offset);
		}
		if (offsets.isEmpty() || offsets.get(offsets.size() - 1) != max) {
			offsets.add(max);
		}
		return offsets;
	}

	private static List<Integer> sampleOffsetsDense(int size) {
		List<Integer> offsets = new ArrayList<>();
		int step = size > 128 ? 8 : size > 64 ? 4 : 2;
		for (int offset = 0; offset < size; offset += step) {
			offsets.add(offset);
		}
		if (offsets.isEmpty() || offsets.get(offsets.size() - 1) != size - 1) {
			offsets.add(size - 1);
		}
		return offsets;
	}

	private static boolean isNearSpawn(ServerWorld world, ChunkPos chunkPos, int radiusChunks) {
		if (radiusChunks <= 0) {
			return false;
		}
		ChunkPos spawnChunk = new ChunkPos(world.getSpawnPos());
		return Math.abs(chunkPos.x - spawnChunk.x) <= radiusChunks && Math.abs(chunkPos.z - spawnChunk.z) <= radiusChunks;
	}

	private static boolean isNearRecentPlacement(ChunkPos chunkPos, int radiusChunks) {
		if (radiusChunks <= 0) {
			return false;
		}
		for (ChunkPos placed : RECENT_PLACEMENTS) {
			if (Math.abs(chunkPos.x - placed.x) <= radiusChunks && Math.abs(chunkPos.z - placed.z) <= radiusChunks) {
				return true;
			}
		}
		return false;
	}

	private static void rememberPlacement(ChunkPos chunkPos) {
		RECENT_PLACEMENTS.add(chunkPos);
		while (RECENT_PLACEMENTS.size() > RECENT_PLACEMENT_LIMIT) {
			RECENT_PLACEMENTS.poll();
		}
	}

	private static String biomeId(ServerWorld world, ChunkPos chunkPos) {
		BlockPos pos = new BlockPos(chunkPos.getCenterX(), world.getSeaLevel(), chunkPos.getCenterZ());
		return world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("");
	}

	private static boolean isFootprintLoaded(ServerWorld world, BlockPos origin, Vec3i size) {
		int minChunkX = Math.floorDiv(origin.getX(), 16);
		int minChunkZ = Math.floorDiv(origin.getZ(), 16);
		int maxChunkX = Math.floorDiv(origin.getX() + size.getX() - 1, 16);
		int maxChunkZ = Math.floorDiv(origin.getZ() + size.getZ() - 1, 16);

		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				if (!world.isChunkLoaded(ChunkPos.toLong(chunkX, chunkZ))) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean isColumnLoaded(ServerWorld world, int x, int z) {
		return world.isChunkLoaded(ChunkPos.toLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
	}

	private static long chunkSeed(ChunkPos pos) {
		long seed = 0x6a09e667f3bcc909L;
		seed ^= (long) pos.x * 0xbf58476d1ce4e5b9L;
		seed ^= (long) pos.z * 0x94d049bb133111ebL;
		seed ^= seed >>> 30;
		return seed;
	}

	private static long spreadSeed(long worldSeed, int regionX, int regionZ, int salt) {
		long seed = worldSeed;
		seed += (long) regionX * 341873128712L;
		seed += (long) regionZ * 132897987541L;
		seed += salt;
		seed ^= seed >>> 33;
		seed *= 0xff51afd7ed558ccdL;
		seed ^= seed >>> 33;
		return seed;
	}

	private static long placementKey(ChunkPos pos, StructureCategory category) {
		return chunkSeed(pos) ^ ((long) category.ordinal() * 0x9e3779b97f4a7c15L);
	}

	private static BlockRotation randomRotation(Random random) {
		return switch (random.nextInt(4)) {
			case 1 -> BlockRotation.CLOCKWISE_90;
			case 2 -> BlockRotation.CLOCKWISE_180;
			case 3 -> BlockRotation.COUNTERCLOCKWISE_90;
			default -> BlockRotation.NONE;
		};
	}

	private static ThreadFactory preparationThreadFactory() {
		return task -> {
			Thread thread = new Thread(task, "Community Structures Preparer");
			thread.setDaemon(true);
			return thread;
		};
	}

	private enum PlacementOutcome {
		PLACED,
		SKIPPED,
		RETRY
	}

	private record PendingPlacement(ChunkPos chunkPos, StructureCategory category, CachedStructure structure, long seed, int attempts) {
	}

	private record PlacementCandidate(StructureCategory category, long seed) {
	}

	private record StructureSnapshot(Vec3i size, NbtList palette, NbtList blocks) {
		int blockCount() {
			return blocks.size();
		}
	}

	private record PlacedBlock(BlockPos offset, BlockState state) {
	}

	private record LandColumn(int x, int z, int bottomY, BlockState bottomState, int topTerrainY) {
	}

	private record LandBlendPlan(int sinkDepth, int skirtRadius, Map<Long, LandColumn> columns, Set<Long> uploadedOffsets, Set<Long> terrainBlockingOffsets) {
	}

	private record EdgeTerrain(int worldY, BlockState sourceState) {
	}

	private record TerrainSurface(int worldY, BlockState state) {
	}

	private record ActivePlacement(StructureCategory category, CachedStructure structure, ChunkPos chunkPos, BlockPos origin, StructureSnapshot snapshot, BlockRotation rotation, Vec3i rotatedSize, BlockState[] palette, LandBlendPlan landBlendPlan, int nextIndex, int scanned) {
	}
}
