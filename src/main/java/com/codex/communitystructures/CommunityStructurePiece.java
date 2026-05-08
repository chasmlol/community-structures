package com.codex.communitystructures;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.structure.StructureContext;
import net.minecraft.structure.StructurePiece;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CommunityStructurePiece extends StructurePiece {
	private static final int BULK_PLACE_FLAGS = Block.FORCE_STATE | Block.SKIP_DROPS;
	private static final int MAX_INLINE_BUCKET_REBUILD_BLOCKS = 50000;
	private static final Map<Path, StructureSnapshot> SNAPSHOT_CACHE = new ConcurrentHashMap<>();

	private final StructureCategory category;
	private final PlacementPreset preset;
	private final String structureId;
	private final String name;
	private final String creatorName;
	private final String creatorId;
	private final Path path;
	private final BlockPos origin;
	private final BlockPos placementOrigin;
	private final BlockRotation rotation;
	private final Vec3i rotatedSize;
	private transient Map<Long, List<RawBlock>> rawBlocksByChunk;
	private transient BlockState[] cachedPalette;
	private transient Set<Long> lootContainerOffsets;

	public CommunityStructurePiece(CachedStructure structure, BlockPos origin, BlockPos placementOrigin, BlockRotation rotation, Vec3i rotatedSize, Map<Long, List<RawBlock>> rawBlocksByChunk) {
		super(
			CommunityStructureWorldgen.PIECE_TYPE,
			0,
			new BlockBox(
				origin.getX(),
				origin.getY(),
				origin.getZ(),
				origin.getX() + rotatedSize.getX() - 1,
				origin.getY() + rotatedSize.getY() - 1,
				origin.getZ() + rotatedSize.getZ() - 1
			)
		);
		this.category = structure.category();
		this.preset = structure.placementPreset();
		this.structureId = structure.id();
		this.name = structure.name();
		this.creatorName = structure.creatorName() == null ? "" : structure.creatorName();
		this.creatorId = structure.creatorId() == null ? "" : structure.creatorId();
		this.path = structure.path();
		this.origin = origin;
		this.placementOrigin = placementOrigin;
		this.rotation = rotation;
		this.rotatedSize = rotatedSize;
		this.rawBlocksByChunk = rawBlocksByChunk;
	}

	public CommunityStructurePiece(NbtCompound nbt) {
		super(CommunityStructureWorldgen.PIECE_TYPE, nbt);
		this.category = StructureCategory.fromApiName(nbt.getString("category")).orElse(StructureCategory.LAND);
		this.preset = PlacementPreset.fromApiName(nbt.getString("preset"), category);
		this.structureId = nbt.getString("structure_id");
		this.name = nbt.getString("name");
		this.creatorName = nbt.getString("creator_name");
		this.creatorId = nbt.getString("creator_id");
		this.path = Path.of(nbt.getString("path"));
		this.origin = new BlockPos(nbt.getInt("origin_x"), nbt.getInt("origin_y"), nbt.getInt("origin_z"));
		this.placementOrigin = nbt.contains("placement_origin_y")
			? new BlockPos(nbt.getInt("placement_origin_x"), nbt.getInt("placement_origin_y"), nbt.getInt("placement_origin_z"))
			: this.origin;
		this.rotation = parseRotation(nbt.getString("rotation"));
		this.rotatedSize = new Vec3i(nbt.getInt("size_x"), nbt.getInt("size_y"), nbt.getInt("size_z"));
	}

	@Override
	protected void writeNbt(StructureContext context, NbtCompound nbt) {
		nbt.putString("category", category.apiName());
		nbt.putString("preset", preset.apiName());
		nbt.putString("structure_id", structureId);
		nbt.putString("name", name);
		nbt.putString("creator_name", creatorName);
		nbt.putString("creator_id", creatorId);
		nbt.putString("path", path.toString());
		nbt.putInt("origin_x", origin.getX());
		nbt.putInt("origin_y", origin.getY());
		nbt.putInt("origin_z", origin.getZ());
		nbt.putInt("placement_origin_x", placementOrigin.getX());
		nbt.putInt("placement_origin_y", placementOrigin.getY());
		nbt.putInt("placement_origin_z", placementOrigin.getZ());
		nbt.putString("rotation", rotation.name());
		nbt.putInt("size_x", rotatedSize.getX());
		nbt.putInt("size_y", rotatedSize.getY());
		nbt.putInt("size_z", rotatedSize.getZ());
	}

	@Override
	public void generate(StructureWorldAccess world, StructureAccessor structureAccessor, ChunkGenerator chunkGenerator, net.minecraft.util.math.random.Random random, BlockBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
		try {
			placeChunkBlocks(world, chunkBox);
		} catch (IOException exception) {
			CommunityStructures.LOGGER.warn("Could not generate community structure piece {} from {}", name, path, exception);
		}
	}

	public static StructureSnapshot readSnapshot(Path path, int maxBytes) throws IOException {
		StructureSnapshot cached = SNAPSHOT_CACHE.get(path);
		if (cached != null) {
			return cached;
		}

		NbtCompound nbt = readStructureNbt(path, maxBytes);
		if (nbt == null) {
			throw new IOException("Structure NBT could not be read");
		}
		NbtList sizeNbt = nbt.getList("size", NbtElement.INT_TYPE);
		if (sizeNbt.size() < 3) {
			throw new IOException("Structure is missing size tag");
		}

		StructureSnapshot snapshot = new StructureSnapshot(
			new Vec3i(sizeNbt.getInt(0), sizeNbt.getInt(1), sizeNbt.getInt(2)),
			nbt.getList("palette", NbtElement.COMPOUND_TYPE),
			nbt.getList("blocks", NbtElement.COMPOUND_TYPE)
		);
		SNAPSHOT_CACHE.put(path, snapshot);
		return snapshot;
	}

	public static void rememberSnapshot(Path path, StructureSnapshot snapshot) {
		SNAPSHOT_CACHE.put(path, snapshot);
	}

	public static void transferSnapshot(Path oldPath, Path newPath, StructureSnapshot snapshot) {
		SNAPSHOT_CACHE.remove(oldPath);
		SNAPSHOT_CACHE.put(newPath, snapshot);
	}

	public static Map<Long, List<RawBlock>> buildRawBlockBuckets(StructureSnapshot snapshot, BlockPos origin, BlockRotation rotation, Vec3i rotatedSize) {
		Map<Long, List<RawBlock>> buckets = new ConcurrentHashMap<>();
		boolean[] placeableStates = placeablePaletteStates(snapshot.palette());
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= placeableStates.length || !placeableStates[stateIndex]) {
				continue;
			}
			Vec3i pos = blockPos(blockNbt);
			if (pos == null) {
				continue;
			}
			BlockPos offset = rotatedOffset(pos.getX(), pos.getY(), pos.getZ(), rotatedSize, rotation);
			BlockPos worldPos = origin.add(offset);
			long chunkKey = ChunkPos.toLong(Math.floorDiv(worldPos.getX(), 16), Math.floorDiv(worldPos.getZ(), 16));
			buckets.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(new RawBlock(worldPos, stateIndex, index));
		}
		return buckets;
	}

	public static int minPlaceableY(StructureSnapshot snapshot) {
		boolean[] placeableStates = placeablePaletteStates(snapshot.palette());
		int minY = Integer.MAX_VALUE;
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= placeableStates.length || !placeableStates[stateIndex]) {
				continue;
			}
			Vec3i pos = blockPos(blockNbt);
			if (pos != null) {
				minY = Math.min(minY, pos.getY());
			}
		}
		return minY == Integer.MAX_VALUE ? 0 : minY;
	}

	public static int surfaceAnchorY(StructureSnapshot snapshot) {
		boolean[] placeableStates = placeablePaletteStates(snapshot.palette());
		boolean[] anchorStates = surfaceAnchorPaletteStates(snapshot.palette());
		Map<Integer, Integer> anchorCountsByY = new HashMap<>();
		int minY = Integer.MAX_VALUE;

		for (int index = 0; index < snapshot.blocks().size(); index++) {
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= placeableStates.length || !placeableStates[stateIndex]) {
				continue;
			}
			Vec3i pos = blockPos(blockNbt);
			if (pos == null) {
				continue;
			}

			minY = Math.min(minY, pos.getY());
			if (anchorStates[stateIndex]) {
				anchorCountsByY.merge(pos.getY(), 1, Integer::sum);
			}
		}

		if (minY == Integer.MAX_VALUE) {
			return 0;
		}
		int fallbackY = minY;
		return anchorCountsByY.entrySet().stream()
			.filter(entry -> entry.getValue() >= 16)
			.map(Map.Entry::getKey)
			.min(Integer::compareTo)
			.orElseGet(() -> anchorCountsByY.entrySet().stream()
				.filter(entry -> entry.getValue() >= 8)
				.map(Map.Entry::getKey)
				.min(Integer::compareTo)
				.orElse(fallbackY));
	}

	public static Vec3i rotatedSize(Vec3i size, BlockRotation rotation) {
		return switch (rotation) {
			case CLOCKWISE_90, COUNTERCLOCKWISE_90 -> new Vec3i(size.getZ(), size.getY(), size.getX());
			default -> size;
		};
	}

	private void placeChunkBlocks(StructureWorldAccess world, BlockBox chunkBox) throws IOException {
		ServerWorld serverWorld = world.toServerWorld();
		CommunityStructureConfig config = CommunityStructures.config();
		StructureSnapshot snapshot = readSnapshot(path, config == null ? 128 * 1024 * 1024 : config.maxDownloadBytes);
		if (rawBlocksByChunk == null) {
			if (snapshot.blocks().size() > MAX_INLINE_BUCKET_REBUILD_BLOCKS) {
				CommunityStructures.LOGGER.warn("Skipping legacy large community structure piece {} with {} blocks because it was loaded without a chunk index", name, snapshot.blocks().size());
				return;
			}
			rawBlocksByChunk = buildRawBlockBuckets(snapshot, placementOrigin, rotation, rotatedSize);
		}
		if (cachedPalette == null) {
			cachedPalette = rotatedPalette(serverWorld, snapshot, rotation);
		}
		if (lootContainerOffsets == null) {
			lootContainerOffsets = lootContainerOffsets(snapshot, cachedPalette, rotation, rotatedSize);
		}
		rememberGeneratedInstance(serverWorld);

		long chunkKey = ChunkPos.toLong(Math.floorDiv(chunkBox.getMinX(), 16), Math.floorDiv(chunkBox.getMinZ(), 16));
		for (RawBlock rawBlock : rawBlocksByChunk.getOrDefault(chunkKey, List.of())) {
			if (rawBlock.stateIndex() < 0 || rawBlock.stateIndex() >= cachedPalette.length || !chunkBox.contains(rawBlock.pos())) {
				continue;
			}

			BlockState state = processedState(serverWorld, rawBlock.pos(), cachedPalette[rawBlock.stateIndex()]);
			if (state == null) {
				continue;
			}
			if (!shouldLetTerrainWin(world, rawBlock.pos(), state)) {
				world.setBlockState(rawBlock.pos(), state, BULK_PLACE_FLAGS);
				NbtCompound blockNbt = rawBlock.blockIndex() >= 0 && rawBlock.blockIndex() < snapshot.blocks().size()
					? snapshot.blocks().getCompound(rawBlock.blockIndex())
					: new NbtCompound();
				CommunityStructureBlockEntities.applyPlacedBlockEntity(
					serverWorld,
					rawBlock.pos(),
					blockNbt.getCompound("nbt"),
					lootContainerOffsets.contains(blockOffsetKey(rawBlock.pos().getX() - placementOrigin.getX(), rawBlock.pos().getY() - placementOrigin.getY(), rawBlock.pos().getZ() - placementOrigin.getZ())),
					serverWorld.getSeed() ^ rawBlock.pos().asLong()
				);
			}
		}
	}

	private void rememberGeneratedInstance(ServerWorld world) {
		if (creatorName == null || creatorName.isBlank() || creatorId == null || creatorId.isBlank() || rawBlocksByChunk == null) {
			return;
		}
		List<BlockPos> positions = new ArrayList<>();
		for (List<RawBlock> chunkBlocks : rawBlocksByChunk.values()) {
			for (RawBlock rawBlock : chunkBlocks) {
				positions.add(rawBlock.pos());
			}
		}
		CommunityStructureInstanceState.forWorld(world).remember(instanceId(), new CachedStructure(category, structureId, name, path, Set.of(), preset, creatorName, creatorId), positions);
	}

	private String instanceId() {
		return path.toString();
	}

	private static boolean[] placeablePaletteStates(NbtList palette) {
		boolean[] states = new boolean[palette.size()];
		for (int index = 0; index < palette.size(); index++) {
			String name = palette.getCompound(index).getString("Name");
			states[index] = !name.equals("minecraft:air") && !name.equals("minecraft:cave_air") && !name.equals("minecraft:void_air");
		}
		return states;
	}

	private static boolean[] surfaceAnchorPaletteStates(NbtList palette) {
		boolean[] states = new boolean[palette.size()];
		for (int index = 0; index < palette.size(); index++) {
			String name = palette.getCompound(index).getString("Name");
			states[index] = !name.equals("minecraft:air")
				&& !name.equals("minecraft:cave_air")
				&& !name.equals("minecraft:void_air")
				&& !name.endsWith("_stairs")
				&& !name.endsWith("_slab")
				&& !name.endsWith("_fence")
				&& !name.endsWith("_fence_gate")
				&& !name.endsWith("_wall")
				&& !name.endsWith("_leaves")
				&& !name.endsWith("_sapling")
				&& !name.endsWith("_grass")
				&& !name.endsWith("_flower")
				&& !name.endsWith("_torch")
				&& !name.endsWith("_lantern")
				&& !name.endsWith("_sign")
				&& !name.endsWith("_door")
				&& !name.endsWith("_trapdoor")
				&& !name.endsWith("_button")
				&& !name.endsWith("_pressure_plate")
				&& !name.endsWith("_carpet")
				&& !name.endsWith("_pane")
				&& !name.contains("vine")
				&& !name.contains("campfire")
				&& !name.contains("chain");
		}
		return states;
	}

	private void blendLandTerrainSkirt(ServerWorld world, BlockBox chunkBox, CommunityStructureConfig config) {
		if (config == null || category != StructureCategory.LAND || !config.landTerrainBlend || !preset.shouldBlendLandTerrain()) {
			return;
		}

		int radius = Math.max(2, Math.min(10, config.landTerrainBlendRadius));
		int targetEdgeY = origin.getY() + switch (preset) {
			case BURIED_RUIN -> 0;
			case SURFACE_RUIN -> 2;
			default -> 1;
		};
		int minX = Math.max(chunkBox.getMinX(), origin.getX() - radius);
		int maxX = Math.min(chunkBox.getMaxX(), origin.getX() + rotatedSize.getX() + radius - 1);
		int minZ = Math.max(chunkBox.getMinZ(), origin.getZ() - radius);
		int maxZ = Math.min(chunkBox.getMaxZ(), origin.getZ() + rotatedSize.getZ() + radius - 1);

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				int localX = x - origin.getX();
				int localZ = z - origin.getZ();
				int distance = distanceOutsideFootprint(localX, localZ);
				if (distance <= 0 || distance > radius) {
					continue;
				}

				int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
				if (surfaceY < world.getBottomY() || surfaceY >= targetEdgeY) {
					continue;
				}

				double weight = 1.0D - (distance - 1.0D) / Math.max(1.0D, radius);
				weight = weight * weight * (3.0D - 2.0D * weight);
				int targetY = Math.min(targetEdgeY, surfaceY + Math.max(1, (int) Math.ceil((targetEdgeY - surfaceY) * weight)));
				targetY = Math.min(targetY, surfaceY + 4);
				BlockState surfaceState = localTerrainSurface(world, x, surfaceY, z);
				BlockState fillState = shallowTerrainFill(surfaceState);

				for (int y = surfaceY + 1; y <= targetY; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!canReplaceForSkirt(world, pos)) {
						continue;
					}
					world.setBlockState(pos, y == targetY ? surfaceState : fillState, BULK_PLACE_FLAGS);
				}
			}
		}
	}

	private int distanceOutsideFootprint(int localX, int localZ) {
		int dx = localX < 0 ? -localX : localX >= rotatedSize.getX() ? localX - rotatedSize.getX() + 1 : 0;
		int dz = localZ < 0 ? -localZ : localZ >= rotatedSize.getZ() ? localZ - rotatedSize.getZ() + 1 : 0;
		return Math.max(dx, dz);
	}

	private BlockState localTerrainSurface(ServerWorld world, int x, int y, int z) {
		BlockPos pos = new BlockPos(x, y, z);
		BlockState state = world.getBlockState(pos);
		if (state.isOf(Blocks.SNOW)) {
			state = world.getBlockState(pos.down());
		}
		if (state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM) || state.isOf(Blocks.MOSS_BLOCK)) {
			return state.getBlock().getDefaultState();
		}
		if (state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.GRAVEL) || state.isOf(Blocks.MUD) || state.isOf(Blocks.CLAY)) {
			return state.getBlock().getDefaultState();
		}
		return Blocks.GRASS_BLOCK.getDefaultState();
	}

	private BlockState shallowTerrainFill(BlockState surfaceState) {
		if (surfaceState.isOf(Blocks.SAND) || surfaceState.isOf(Blocks.RED_SAND) || surfaceState.isOf(Blocks.GRAVEL) || surfaceState.isOf(Blocks.MUD) || surfaceState.isOf(Blocks.CLAY)) {
			return surfaceState.getBlock().getDefaultState();
		}
		return Blocks.DIRT.getDefaultState();
	}

	private boolean canReplaceForSkirt(ServerWorld world, BlockPos pos) {
		BlockState state = world.getBlockState(pos);
		return state.isAir() || !world.getFluidState(pos).isEmpty() || !state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static BlockState[] rotatedPalette(ServerWorld world, StructureSnapshot snapshot, BlockRotation rotation) {
		NbtList paletteNbt = snapshot.palette();
		BlockState[] palette = new BlockState[paletteNbt.size()];
		for (int index = 0; index < paletteNbt.size(); index++) {
			palette[index] = NbtHelper.toBlockState(world.createCommandRegistryWrapper(RegistryKeys.BLOCK), paletteNbt.getCompound(index)).rotate(rotation);
		}
		return palette;
	}

	private static Set<Long> lootContainerOffsets(StructureSnapshot snapshot, BlockState[] palette, BlockRotation rotation, Vec3i rotatedSize) {
		Set<Long> offsets = ConcurrentHashMap.newKeySet();
		for (int index = 0; index < snapshot.blocks().size(); index++) {
			if (offsets.size() >= CommunityStructureBlockEntities.MAX_LOOT_CONTAINERS) {
				break;
			}
			NbtCompound blockNbt = snapshot.blocks().getCompound(index);
			int stateIndex = blockNbt.getInt("state");
			if (stateIndex < 0 || stateIndex >= palette.length || !CommunityStructureBlockEntities.isLikelyLootContainerState(palette[stateIndex])) {
				continue;
			}
			Vec3i pos = blockPos(blockNbt);
			if (pos == null) {
				continue;
			}
			BlockPos offset = rotatedOffset(pos.getX(), pos.getY(), pos.getZ(), rotatedSize, rotation);
			offsets.add(blockOffsetKey(offset.getX(), offset.getY(), offset.getZ()));
		}
		return offsets;
	}

	private BlockState processedState(ServerWorld world, BlockPos pos, BlockState state) {
		if (category == StructureCategory.CAVE) {
			return state;
		}
		if (category == StructureCategory.WATER && pos.getY() < world.getSeaLevel()) {
			if (state.isAir()) {
				return Blocks.WATER.getDefaultState();
			}
			if (state.contains(Properties.WATERLOGGED)) {
				return state.with(Properties.WATERLOGGED, true);
			}
		}
		return state.isAir() ? null : state;
	}

	private boolean shouldLetTerrainWin(StructureWorldAccess world, BlockPos pos, BlockState state) {
		if (category != StructureCategory.LAND || state.isAir() || state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
			return false;
		}
		BlockState existing = world.getBlockState(pos);
		return !existing.isAir()
			&& world.getFluidState(pos).isEmpty()
			&& existing.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	private static NbtCompound readStructureNbt(Path path, int maxBytes) throws IOException {
		try {
			return NbtIo.readCompressed(path, NbtSizeTracker.of(maxBytes));
		} catch (IOException compressedException) {
			return NbtIo.read(path);
		}
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

	private static BlockPos rotatedOffset(int x, int y, int z, Vec3i rotatedSize, BlockRotation rotation) {
		return switch (rotation) {
			case CLOCKWISE_90 -> new BlockPos(rotatedSize.getX() - 1 - z, y, x);
			case CLOCKWISE_180 -> new BlockPos(rotatedSize.getX() - 1 - x, y, rotatedSize.getZ() - 1 - z);
			case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, rotatedSize.getZ() - 1 - x);
			default -> new BlockPos(x, y, z);
		};
	}

	private static long blockOffsetKey(int x, int y, int z) {
		return BlockPos.asLong(x, y, z);
	}

	private static BlockRotation parseRotation(String value) {
		if (value == null || value.isBlank()) {
			return BlockRotation.NONE;
		}
		try {
			return BlockRotation.valueOf(value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return BlockRotation.NONE;
		}
	}

	public record StructureSnapshot(Vec3i size, NbtList palette, NbtList blocks) {
	}

	public record RawBlock(BlockPos pos, int stateIndex, int blockIndex) {
	}
}
