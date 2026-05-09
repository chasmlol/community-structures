package com.codex.communitystructures;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.structure.StructureType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CommunityConfiguredStructure extends Structure {
	private static final Codec<StructureCategory> CATEGORY_CODEC = Codec.STRING.xmap(
		value -> StructureCategory.fromApiName(value).orElse(StructureCategory.LAND),
		StructureCategory::apiName
	);
	private static final Codec<PlacementPreset> PRESET_CODEC = Codec.STRING.xmap(
		value -> PlacementPreset.fromApiName(value, StructureCategory.LAND),
		PlacementPreset::apiName
	);
	public static final MapCodec<CommunityConfiguredStructure> CODEC = RecordCodecBuilder.mapCodec(instance ->
		instance.group(
			configCodecBuilder(instance),
			CATEGORY_CODEC.fieldOf("category").forGetter(CommunityConfiguredStructure::category),
			PRESET_CODEC.fieldOf("placement_preset").forGetter(CommunityConfiguredStructure::preset)
		).apply(instance, CommunityConfiguredStructure::new)
	);

	private static final int LAND_SALT = 0x18f2a6b1;
	private static final int WATER_SALT = 0x2b7e1516;
	private static final int CAVE_SALT = 0x3c6ef372;
	private static final int SURFACE_RUIN_SALT = 0x4a7c15d9;
	private static final int BURIED_RUIN_SALT = 0x5f3759df;
	private static final int BRIDGE_SALT = 0x1badd00d;
	private static final int RECENT_START_LIMIT = 512;
	private static final Queue<ChunkPos> RECENT_STARTS = new ConcurrentLinkedQueue<>();
	private static final AtomicLong START_WINDOW_SECOND = new AtomicLong(-1L);
	private static final AtomicInteger STARTS_IN_WINDOW = new AtomicInteger();

	private final StructureCategory category;
	private final PlacementPreset preset;

	public CommunityConfiguredStructure(Config config, StructureCategory category, PlacementPreset preset) {
		super(config);
		this.category = category;
		this.preset = preset;
	}

	@Override
	protected Optional<StructurePosition> getStructurePosition(Context context) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null || !config.enabled || !config.useBuiltInStructureGeneration) {
			return Optional.empty();
		}
		if (!matchesConfiguredSpread(context, config)) {
			return Optional.empty();
		}
		if (isNearOriginSpawn(context.chunkPos(), config.spawnProtectionRadiusChunks)) {
			return Optional.empty();
		}
		if (isNearRecentStart(context.chunkPos(), config.minPlacementDistanceChunks)) {
			return Optional.empty();
		}
		if (!hasStartCapacity(config)) {
			return Optional.empty();
		}

		RegistryEntry<Biome> centerBiome = biomeAt(context, context.chunkPos().getCenterX(), context.chunkGenerator().getSeaLevel(), context.chunkPos().getCenterZ());
		if (!biomeMatchesCategory(centerBiome)) {
			return Optional.empty();
		}
		String biomeId = centerBiome.getKey().map(key -> key.getValue().toString()).orElse("");

		Optional<CachedStructure> reserved = CommunityStructures.cache().choose(category, preset, context.random(), biomeId);
		if (reserved.isEmpty()) {
			return Optional.empty();
		}
		if (!claimStartSlot(config)) {
			CommunityStructures.cache().release(reserved.get());
			return Optional.empty();
		}

		CachedStructure cached = reserved.get();
		try {
			CommunityStructurePiece.StructureSnapshot snapshot = CommunityStructurePiece.readSnapshot(cached.path(), config.maxDownloadBytes);
			PlacementPlan plan = placementPlan(context, snapshot, config);
			if (plan == null) {
				CommunityStructures.cache().release(cached);
				return Optional.empty();
			}
			BlockRotation rotation = plan.rotation();
			Vec3i rotatedSize = plan.rotatedSize();
			if (rotatedSize.getX() > config.maxStructureWidth || rotatedSize.getZ() > config.maxStructureWidth || rotatedSize.getY() > config.maxStructureHeight) {
				CommunityStructures.LOGGER.info("Skipping oversized {} community structure {} with rotated size {}", category.apiName(), cached.name(), rotatedSize);
				CommunityStructures.cache().release(cached);
				return Optional.empty();
			}
			BlockPos origin = plan.origin();
			BlockPos placementOrigin = applyFinalWorldOffset(origin);

			Optional<CachedStructure> generated = CommunityStructures.cache().materializeForWorldgen(cached);
			if (generated.isEmpty()) {
				return Optional.empty();
			}

			CachedStructure generatedStructure = generated.get();
			CommunityStructurePiece.transferSnapshot(cached.path(), generatedStructure.path(), snapshot);
			Map<Long, List<CommunityStructurePiece.RawBlock>> rawBlocksByChunk = CommunityStructurePiece.buildRawBlockBuckets(snapshot, placementOrigin, rotation, rotatedSize);
			CommunityStructures.LOGGER.debug(
				"Started built-in {} community structure {} at {} placing blocks at {} using preset {} with worldYOffset {}",
				category.apiName(),
				generatedStructure.name(),
				origin.toShortString(),
				placementOrigin.toShortString(),
				preset.apiName(),
				finalWorldYOffset()
			);
			rememberStart(context.chunkPos());
			return Optional.of(new StructurePosition(origin, collector -> collector.addPiece(new CommunityStructurePiece(generatedStructure, origin, placementOrigin, rotation, rotatedSize, rawBlocksByChunk))));
		} catch (IOException exception) {
			CommunityStructures.cache().release(cached);
			CommunityStructures.LOGGER.warn("Could not read cached community structure {}", cached.path(), exception);
			return Optional.empty();
		}
	}

	@Override
	public StructureType<?> getType() {
		return CommunityStructureWorldgen.STRUCTURE_TYPE;
	}

	private boolean matchesConfiguredSpread(Context context, CommunityStructureConfig config) {
		int spacing = switch (category) {
			case LAND -> config.landSpacingChunks;
			case WATER -> config.waterSpacingChunks;
			case CAVE -> config.caveSpacingChunks;
		};
		int separation = switch (category) {
			case LAND -> config.landSeparationChunks;
			case WATER -> config.waterSeparationChunks;
			case CAVE -> config.caveSeparationChunks;
		};
		double chance = switch (category) {
			case LAND -> config.landChancePerChunk;
			case WATER -> config.waterChancePerChunk;
			case CAVE -> config.caveChancePerChunk;
		};
		if (chance <= 0.0D) {
			return false;
		}

		int spread = Math.max(1, spacing - separation);
		ChunkPos chunkPos = context.chunkPos();
		int regionX = Math.floorDiv(chunkPos.x, spacing);
		int regionZ = Math.floorDiv(chunkPos.z, spacing);
		long seed = spreadSeed(context.seed(), regionX, regionZ, salt());
		Random random = Random.create(seed);
		int candidateX = regionX * spacing + random.nextInt(spread);
		int candidateZ = regionZ * spacing + random.nextInt(spread);
		return chunkPos.x == candidateX && chunkPos.z == candidateZ && random.nextDouble() < chance;
	}

	private boolean biomeMatchesCategory(RegistryEntry<Biome> biome) {
		if (category == StructureCategory.LAND && preset == PlacementPreset.BRIDGE) {
			return biome.isIn(BiomeTags.IS_OVERWORLD) && !biome.isIn(BiomeTags.IS_OCEAN);
		}
		return switch (category) {
			case LAND -> !biome.isIn(BiomeTags.IS_OCEAN) && !biome.isIn(BiomeTags.IS_RIVER);
			case WATER -> biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_DEEP_OCEAN);
			case CAVE -> biome.isIn(BiomeTags.IS_OVERWORLD);
		};
	}

	private PlacementPlan placementPlan(Context context, CommunityStructurePiece.StructureSnapshot snapshot, CommunityStructureConfig config) {
		if (category == StructureCategory.LAND && preset == PlacementPreset.BRIDGE) {
			return bridgePlacementPlan(context, snapshot, config).orElse(null);
		}
		BlockRotation rotation = BlockRotation.random(context.random());
		Vec3i rotatedSize = CommunityStructurePiece.rotatedSize(snapshot.size(), rotation);
		BlockPos footprintOrigin = centeredFootprintOrigin(context.chunkPos(), rotatedSize);
		BlockPos origin = switch (category) {
			case LAND -> landOrigin(context, footprintOrigin, rotatedSize, config);
			case WATER -> waterOrigin(context, footprintOrigin, rotatedSize);
			case CAVE -> caveOrigin(context, footprintOrigin, rotatedSize, config);
		};
		return origin == null ? null : new PlacementPlan(origin, rotation, rotatedSize);
	}

	private Optional<PlacementPlan> bridgePlacementPlan(Context context, CommunityStructurePiece.StructureSnapshot snapshot, CommunityStructureConfig config) {
		List<BlockRotation> rotations = context.random().nextBoolean()
			? List.of(BlockRotation.NONE, BlockRotation.CLOCKWISE_90)
			: List.of(BlockRotation.CLOCKWISE_90, BlockRotation.NONE);
		for (BlockRotation rotation : rotations) {
			Vec3i rotatedSize = CommunityStructurePiece.rotatedSize(snapshot.size(), rotation);
			if (rotatedSize.getX() > config.maxStructureWidth || rotatedSize.getZ() > config.maxStructureWidth || rotatedSize.getY() > config.maxStructureHeight) {
				continue;
			}
			CommunityStructurePiece.Footprint footprint = CommunityStructurePiece.placeableFootprint(snapshot, rotation);
			Optional<BlockPos> footprintOrigin = bridgeOrigin(context, footprint.size());
			if (footprintOrigin.isPresent()) {
				Vec3i minOffset = footprint.minOffset();
				BlockPos origin = footprintOrigin.get().add(-minOffset.getX(), -minOffset.getY(), -minOffset.getZ());
				return Optional.of(new PlacementPlan(origin, rotation, rotatedSize));
			}
		}
		return Optional.empty();
	}

	private Optional<BlockPos> bridgeOrigin(Context context, Vec3i size) {
		boolean alongZ = size.getZ() >= size.getX();
		int length = alongZ ? size.getZ() : size.getX();
		int width = alongZ ? size.getX() : size.getZ();
		if (length < 6 || width < 1) {
			return Optional.empty();
		}

		int chunkStartX = context.chunkPos().getStartX();
		int chunkStartZ = context.chunkPos().getStartZ();
		int seaLevel = context.chunkGenerator().getSeaLevel();
		if (!bridgeChunkHasWaterNearby(context, size, seaLevel)) {
			return Optional.empty();
		}
		List<Integer> minorOffsets = bridgeMinorOffsets(width);
		int requiredBankBlocks = Math.max(1, Math.min(minorOffsets.size(), minorOffsets.size() / 2 + 1));

		for (int attempt = 0; attempt < 18; attempt++) {
			int minorCenter = context.random().nextInt(16);
			int majorStart = context.random().nextInt(16);
			BlockPos origin = alongZ
				? new BlockPos(chunkStartX + minorCenter - width / 2, 0, chunkStartZ + majorStart)
				: new BlockPos(chunkStartX + majorStart, 0, chunkStartZ + minorCenter - width / 2);

			BridgeBank startBank = bridgeBank(context, origin, alongZ, 0, width, seaLevel);
			if (startBank.solidBlocks() < requiredBankBlocks) {
				continue;
			}
			BridgeBank endBank = bridgeBank(context, origin, alongZ, length - 1, width, seaLevel);
			if (endBank.solidBlocks() < requiredBankBlocks || Math.abs(startBank.averageY() - endBank.averageY()) > 3) {
				continue;
			}
			if (!bridgeWaterPassage(context, origin, alongZ, length, width, seaLevel)) {
				continue;
			}

			int y = Math.max(seaLevel + 1, (startBank.averageY() + endBank.averageY()) / 2 - 1);
			return Optional.of(new BlockPos(origin.getX(), y, origin.getZ()));
		}
		return Optional.empty();
	}

	private boolean bridgeChunkHasWaterNearby(Context context, Vec3i size, int seaLevel) {
		int centerX = context.chunkPos().getCenterX();
		int centerZ = context.chunkPos().getCenterZ();
		int range = Math.min(24, Math.max(8, Math.max(size.getX(), size.getZ())));
		int[][] samples = {
			{0, 0},
			{8, 0},
			{-8, 0},
			{0, 8},
			{0, -8},
			{range, 0},
			{-range, 0},
			{0, range},
			{0, -range}
		};
		for (int[] sample : samples) {
			if (bridgeIsWater(context, centerX + sample[0], centerZ + sample[1], seaLevel)) {
				return true;
			}
		}
		return false;
	}

	private BridgeBank bridgeBank(Context context, BlockPos origin, boolean alongZ, int majorOffset, int width, int seaLevel) {
		int solid = 0;
		int totalY = 0;
		for (int minorOffset : bridgeMinorOffsets(width)) {
			int x = alongZ ? origin.getX() + minorOffset : origin.getX() + majorOffset;
			int z = alongZ ? origin.getZ() + majorOffset : origin.getZ() + minorOffset;
			Optional<Integer> bankY = bridgeBankY(context, x, z, seaLevel);
			if (bankY.isPresent()) {
				solid++;
				totalY += bankY.get();
			}
		}
		return new BridgeBank(solid, solid == 0 ? seaLevel + 1 : totalY / solid);
	}

	private Optional<Integer> bridgeBankY(Context context, int x, int z, int seaLevel) {
		if (bridgeIsWater(context, x, z, seaLevel)) {
			return Optional.empty();
		}
		RegistryEntry<Biome> biome = biomeAt(context, x, seaLevel, z);
		if (biome.isIn(BiomeTags.IS_OCEAN)) {
			return Optional.empty();
		}
		int y = context.chunkGenerator().getHeight(x, z, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, context.world(), context.noiseConfig());
		return y >= seaLevel && y <= seaLevel + 5 ? Optional.of(y) : Optional.empty();
	}

	private boolean bridgeWaterPassage(Context context, BlockPos origin, boolean alongZ, int length, int width, int seaLevel) {
		int waterStart = Math.max(1, Math.min(length - 2, length / 5));
		int waterEnd = Math.max(waterStart, length - 1 - waterStart);
		int checks = 0;
		int water = 0;
		int step = Math.max(1, (waterEnd - waterStart + 1) / 10);
		for (int majorOffset = waterStart; majorOffset <= waterEnd; majorOffset += step) {
			for (int minorOffset : bridgeMinorOffsets(width)) {
				int x = alongZ ? origin.getX() + minorOffset : origin.getX() + majorOffset;
				int z = alongZ ? origin.getZ() + majorOffset : origin.getZ() + minorOffset;
				checks++;
				if (bridgeIsWater(context, x, z, seaLevel)) {
					water++;
				}
			}
		}
		return checks > 0 && water * 100 >= checks * 85;
	}

	private boolean bridgeIsWater(Context context, int x, int z, int seaLevel) {
		RegistryEntry<Biome> biome = biomeAt(context, x, seaLevel, z);
		if (biome.isIn(BiomeTags.IS_OCEAN)) {
			return false;
		}
		int floorY = context.chunkGenerator().getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig());
		int surfaceY = context.chunkGenerator().getHeight(x, z, Heightmap.Type.WORLD_SURFACE_WG, context.world(), context.noiseConfig());
		return floorY <= seaLevel && surfaceY <= seaLevel + 1;
	}

	private List<Integer> bridgeMinorOffsets(int width) {
		List<Integer> offsets = new ArrayList<>();
		int center = width / 2;
		int radius = Math.min(center, 3);
		for (int offset = center - radius; offset <= center + radius && offset < width; offset++) {
			if (offset >= 0) {
				offsets.add(offset);
			}
		}
		if (offsets.isEmpty()) {
			offsets.add(0);
		}
		return offsets;
	}

	private BlockPos landOrigin(Context context, BlockPos footprintOrigin, Vec3i size, CommunityStructureConfig config) {
		List<Integer> heights = new ArrayList<>();
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		int totalSamples = 0;
		int drySamples = 0;
		int seaLevel = context.chunkGenerator().getSeaLevel();

		for (int dx : sampleOffsets(size.getX())) {
			for (int dz : sampleOffsets(size.getZ())) {
				totalSamples++;
				int x = footprintOrigin.getX() + dx;
				int z = footprintOrigin.getZ() + dz;
				RegistryEntry<Biome> biome = biomeAt(context, x, seaLevel, z);
				if (biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_RIVER)) {
					continue;
				}

				int y = context.chunkGenerator().getHeight(x, z, Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, context.world(), context.noiseConfig());
				if (y <= seaLevel) {
					continue;
				}
				drySamples++;
				heights.add(y);
				minHeight = Math.min(minHeight, y);
				maxHeight = Math.max(maxHeight, y);
			}
		}

		int minimumDryPercent = size.getX() > 64 || size.getZ() > 64 ? 50 : 60;
		if (heights.isEmpty() || drySamples * 100 < totalSamples * minimumDryPercent || maxHeight - minHeight > preset.landSlopeLimit(config)) {
			return null;
		}

		heights.sort(Comparator.naturalOrder());
		int medianY = heights.get(heights.size() / 2);
		return new BlockPos(footprintOrigin.getX(), medianY - preset.landExtraSink(), footprintOrigin.getZ());
	}

	private BlockPos applyFinalWorldOffset(BlockPos origin) {
		int yOffset = finalWorldYOffset();
		return yOffset == 0 ? origin : origin.add(0, yOffset, 0);
	}

	private int finalWorldYOffset() {
		return category == StructureCategory.LAND && preset == PlacementPreset.SURFACE_HOUSE ? -1 : 0;
	}

	private BlockPos waterOrigin(Context context, BlockPos footprintOrigin, Vec3i size) {
		List<Integer> floorHeights = new ArrayList<>();
		int oceanSamples = 0;
		int totalSamples = 0;
		int seaLevel = context.chunkGenerator().getSeaLevel();

		for (int dx : sampleOffsets(size.getX())) {
			for (int dz : sampleOffsets(size.getZ())) {
				totalSamples++;
				int x = footprintOrigin.getX() + dx;
				int z = footprintOrigin.getZ() + dz;
				RegistryEntry<Biome> biome = biomeAt(context, x, seaLevel, z);
				if (!biome.isIn(BiomeTags.IS_OCEAN) && !biome.isIn(BiomeTags.IS_DEEP_OCEAN)) {
					continue;
				}
				oceanSamples++;
				floorHeights.add(context.chunkGenerator().getHeight(x, z, Heightmap.Type.OCEAN_FLOOR_WG, context.world(), context.noiseConfig()));
			}
		}

		int minimumOceanPercent = size.getX() > 64 || size.getZ() > 64 ? 60 : 75;
		if (totalSamples == 0 || oceanSamples * 100 < totalSamples * minimumOceanPercent || floorHeights.isEmpty()) {
			return null;
		}
		floorHeights.sort(Comparator.naturalOrder());
		return new BlockPos(footprintOrigin.getX(), floorHeights.get(floorHeights.size() / 2), footprintOrigin.getZ());
	}

	private BlockPos caveOrigin(Context context, BlockPos footprintOrigin, Vec3i size, CommunityStructureConfig config) {
		int bottomY = context.world().getBottomY() + 4;
		int topY = context.world().getBottomY() + context.world().getHeight() - size.getY() - 4;
		int minY = Math.max(bottomY, config.minCaveY);
		int maxY = Math.min(topY, config.maxCaveY);
		if (minY > maxY) {
			return null;
		}
		int y = minY + context.random().nextInt(maxY - minY + 1);
		return new BlockPos(footprintOrigin.getX(), y, footprintOrigin.getZ());
	}

	private RegistryEntry<Biome> biomeAt(Context context, int x, int y, int z) {
		return context.biomeSource().getBiome(
			BiomeCoords.fromBlock(x),
			BiomeCoords.fromBlock(y),
			BiomeCoords.fromBlock(z),
			context.noiseConfig().getMultiNoiseSampler()
		);
	}

	private BlockPos centeredFootprintOrigin(ChunkPos chunkPos, Vec3i size) {
		return new BlockPos(chunkPos.getCenterX() - size.getX() / 2, 0, chunkPos.getCenterZ() - size.getZ() / 2);
	}

	private List<Integer> sampleOffsets(int size) {
		List<Integer> offsets = new ArrayList<>();
		int step = size > 128 ? 12 : size > 64 ? 8 : 4;
		for (int offset = 0; offset < size; offset += step) {
			offsets.add(offset);
		}
		if (offsets.isEmpty() || offsets.get(offsets.size() - 1) != size - 1) {
			offsets.add(size - 1);
		}
		return offsets;
	}

	private boolean isNearOriginSpawn(ChunkPos chunkPos, int radiusChunks) {
		return radiusChunks > 0 && Math.abs(chunkPos.x) <= radiusChunks && Math.abs(chunkPos.z) <= radiusChunks;
	}

	private boolean isNearRecentStart(ChunkPos chunkPos, int radiusChunks) {
		if (radiusChunks <= 0) {
			return false;
		}
		for (ChunkPos recent : RECENT_STARTS) {
			if (Math.abs(chunkPos.x - recent.x) <= radiusChunks && Math.abs(chunkPos.z - recent.z) <= radiusChunks) {
				return true;
			}
		}
		return false;
	}

	private void rememberStart(ChunkPos chunkPos) {
		RECENT_STARTS.add(chunkPos);
		while (RECENT_STARTS.size() > RECENT_START_LIMIT) {
			RECENT_STARTS.poll();
		}
	}

	private boolean claimStartSlot(CommunityStructureConfig config) {
		long second = System.currentTimeMillis() / 1000L;
		long current = START_WINDOW_SECOND.get();
		if (current != second && START_WINDOW_SECOND.compareAndSet(current, second)) {
			STARTS_IN_WINDOW.set(0);
		}
		return STARTS_IN_WINDOW.incrementAndGet() <= config.maxWorldgenStartsPerSecond;
	}

	private boolean hasStartCapacity(CommunityStructureConfig config) {
		long second = System.currentTimeMillis() / 1000L;
		long current = START_WINDOW_SECOND.get();
		if (current != second) {
			return true;
		}
		return STARTS_IN_WINDOW.get() < config.maxWorldgenStartsPerSecond;
	}

	private int salt() {
		return switch (preset) {
			case SURFACE_RUIN -> SURFACE_RUIN_SALT;
			case BURIED_RUIN -> BURIED_RUIN_SALT;
			case BRIDGE -> BRIDGE_SALT;
			default -> switch (category) {
				case LAND -> LAND_SALT;
				case WATER -> WATER_SALT;
				case CAVE -> CAVE_SALT;
			};
		};
	}

	private long spreadSeed(long worldSeed, int regionX, int regionZ, int salt) {
		long seed = worldSeed;
		seed += (long) regionX * 341873128712L;
		seed += (long) regionZ * 132897987541L;
		seed += salt;
		seed ^= seed >>> 33;
		seed *= 0xff51afd7ed558ccdL;
		seed ^= seed >>> 33;
		return seed;
	}

	private StructureCategory category() {
		return category;
	}

	private PlacementPreset preset() {
		return preset;
	}

	private record PlacementPlan(BlockPos origin, BlockRotation rotation, Vec3i rotatedSize) {
	}

	private record BridgeBank(int solidBlocks, int averageY) {
	}
}
