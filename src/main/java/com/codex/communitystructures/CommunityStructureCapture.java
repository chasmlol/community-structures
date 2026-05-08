package com.codex.communitystructures;

import com.google.gson.Gson;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CommunityStructureCapture {
	private static final int SELECTION_SIZE = 20;
	private static final int SELECTION_HEIGHT = 20;
	private static final int SELECTION_NEGATIVE_RADIUS = (SELECTION_SIZE - 1) / 2;
	private static final int SELECTION_POSITIVE_RADIUS = SELECTION_SIZE / 2;
	private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final List<PendingPlacement> PENDING_PLACEMENTS = new ArrayList<>();
	private static final Map<UUID, ActiveCapture> ACTIVE_CAPTURES = new ConcurrentHashMap<>();

	private CommunityStructureCapture() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world instanceof ServerWorld serverWorld) {
				Item item = player.getStackInHand(hand).getItem();
				queuePossiblePlacement(serverWorld, item, hitResult);
			}
			return ActionResult.PASS;
		});

		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
			if (world instanceof ServerWorld serverWorld) {
				PlayerPlacedBlocksState.forWorld(serverWorld).forget(pos);
			}
		});

		ServerTickEvents.END_SERVER_TICK.register(CommunityStructureCapture::processPendingPlacements);
		ServerPlayNetworking.registerGlobalReceiver(CommunityStructureCapturePackets.CaptureActionPayload.ID, (payload, context) -> {
			if (payload.action() == CommunityStructureCapturePackets.ACTION_CANCEL) {
				cancel(context.player());
			} else {
				toggle(context.player());
			}
		});
	}

	private static void queuePossiblePlacement(ServerWorld world, Item item, BlockHitResult hitResult) {
		Block placedBlock = item instanceof BlockItem blockItem ? blockItem.getBlock() : null;
		if (placedBlock == null && item == null) {
			return;
		}

		List<BlockSample> samples = samplePlacementArea(world, hitResult);
		if (!samples.isEmpty()) {
			PENDING_PLACEMENTS.add(new PendingPlacement(world.getRegistryKey(), placedBlock, samples));
		}
	}

	private static List<BlockSample> samplePlacementArea(ServerWorld world, BlockHitResult hitResult) {
		Set<Long> seen = new HashSet<>();
		List<BlockSample> samples = new ArrayList<>();
		BlockPos hit = hitResult.getBlockPos();
		Direction side = hitResult.getSide();
		addPlacementSamples(world, hit, seen, samples);
		addPlacementSamples(world, hit.offset(side), seen, samples);
		return samples;
	}

	private static void addPlacementSamples(ServerWorld world, BlockPos center, Set<Long> seen, List<BlockSample> samples) {
		for (int x = center.getX() - 1; x <= center.getX() + 1; x++) {
			for (int y = center.getY() - 1; y <= center.getY() + 2; y++) {
				for (int z = center.getZ() - 1; z <= center.getZ() + 1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (seen.add(pos.asLong())) {
						samples.add(new BlockSample(pos, world.getBlockState(pos)));
					}
				}
			}
		}
	}

	private static void processPendingPlacements(MinecraftServer server) {
		if (PENDING_PLACEMENTS.isEmpty()) {
			return;
		}

		List<PendingPlacement> pending = List.copyOf(PENDING_PLACEMENTS);
		PENDING_PLACEMENTS.clear();
		for (PendingPlacement placement : pending) {
			ServerWorld world = server.getWorld(placement.worldKey());
			if (world == null) {
				continue;
			}
			PlayerPlacedBlocksState placedBlocks = PlayerPlacedBlocksState.forWorld(world);
			for (BlockSample sample : placement.samples()) {
				BlockState after = world.getBlockState(sample.pos());
				if (shouldRememberPlacedBlock(placement.placedBlock(), sample.before(), after)) {
					placedBlocks.remember(sample.pos());
				}
			}
		}
	}

	private static boolean shouldRememberPlacedBlock(Block placedBlock, BlockState before, BlockState after) {
		if (after.isAir() || after.equals(before)) {
			return false;
		}
		if (placedBlock != null && after.isOf(placedBlock)) {
			return true;
		}
		return before.isAir() || before.isReplaceable();
	}

	private static void toggle(ServerPlayerEntity player) {
		UUID playerId = player.getUuid();
		if (ACTIVE_CAPTURES.containsKey(playerId)) {
			confirm(player);
		} else {
			start(player);
		}
	}

	private static void start(ServerPlayerEntity player) {
		ActiveCapture capture = ActiveCapture.around(player);
		CapturedStructure selected = collect(player.getServerWorld(), capture);
		ACTIVE_CAPTURES.put(player.getUuid(), capture);
		sendPreview(player, capture.preview(selected));
		if (selected.blocks().isEmpty()) {
			player.sendMessage(Text.literal("Selected a 5x5 capture box, but it has 0 tracked player-placed blocks. Only blocks placed after this update can be detected."), false);
			return;
		}
		player.sendMessage(Text.literal("Selected " + selected.blocks().size() + " tracked blocks. Press K again to upload, or J to cancel."), false);
	}

	private static void confirm(ServerPlayerEntity player) {
		ActiveCapture capture = ACTIVE_CAPTURES.remove(player.getUuid());
		if (capture == null) {
			start(player);
			return;
		}

		sendPreview(player, CapturePreview.inactive());
		CapturedStructure selected = collect(player.getServerWorld(), capture);
		if (selected.blocks().isEmpty()) {
			player.sendMessage(Text.literal("Capture cancelled: no tracked player-placed blocks were found in that 5x5 area."), false);
			return;
		}
		Optional<NonVanillaBlock> nonVanillaBlock = firstNonVanillaBlock(selected);
		if (nonVanillaBlock.isPresent()) {
			NonVanillaBlock block = nonVanillaBlock.get();
			player.sendMessage(Text.literal("Capture cancelled: modded block " + block.id() + " was found at " + block.pos().toShortString() + ". Only vanilla Minecraft blocks can be uploaded."), false);
			return;
		}

		byte[] nbt;
		try {
			nbt = writeStructureNbt(selected);
		} catch (IOException exception) {
			CommunityStructures.LOGGER.warn("Could not write captured structure NBT", exception);
			player.sendMessage(Text.literal("Capture failed: could not write structure NBT."), false);
			return;
		}

		String name = captureName(player);
		player.sendMessage(Text.literal("Uploading " + selected.blocks().size() + " captured blocks to the structure database..."), false);
		upload(player, name, nbt, selected.blocks().size());
	}

	private static void cancel(ServerPlayerEntity player) {
		ACTIVE_CAPTURES.remove(player.getUuid());
		sendPreview(player, CapturePreview.inactive());
		player.sendMessage(Text.literal("Structure capture cancelled."), false);
	}

	private static CapturedStructure collect(ServerWorld world, ActiveCapture capture) {
		PlayerPlacedBlocksState placedBlocks = PlayerPlacedBlocksState.forWorld(world);
		List<CapturedBlock> blocks = new ArrayList<>();
		int scanTop = Math.min(world.getTopY() - 1, capture.maxY());

		for (int y = Math.max(world.getBottomY(), capture.floorY()); y <= scanTop; y++) {
			for (int x = capture.minX(); x <= capture.maxX(); x++) {
				for (int z = capture.minZ(); z <= capture.maxZ(); z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!placedBlocks.contains(pos)) {
						continue;
					}
					BlockState state = world.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					blocks.add(new CapturedBlock(pos, state));
				}
			}
		}

		return new CapturedStructure(capture.min(), capture.max(), blocks);
	}

	private static Optional<NonVanillaBlock> firstNonVanillaBlock(CapturedStructure selected) {
		for (CapturedBlock block : selected.blocks()) {
			Identifier id = Registries.BLOCK.getId(block.state().getBlock());
			if (!"minecraft".equals(id.getNamespace())) {
				return Optional.of(new NonVanillaBlock(block.pos(), id));
			}
		}
		return Optional.empty();
	}

	private static byte[] writeStructureNbt(CapturedStructure selected) throws IOException {
		NbtCompound root = new NbtCompound();
		root.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
		root.put("size", intList(selected.sizeX(), selected.sizeY(), selected.sizeZ()));

		NbtList palette = new NbtList();
		NbtList blocks = new NbtList();
		Map<NbtCompoundKey, Integer> paletteIndexes = new LinkedHashMap<>();

		for (CapturedBlock block : selected.blocks()) {
			NbtCompound stateNbt = NbtHelper.fromBlockState(block.state());
			NbtCompoundKey key = new NbtCompoundKey(stateNbt.toString());
			Integer stateIndex = paletteIndexes.get(key);
			if (stateIndex == null) {
				stateIndex = paletteIndexes.size();
				paletteIndexes.put(key, stateIndex);
				palette.add(stateNbt);
			}

			NbtCompound blockNbt = new NbtCompound();
			blockNbt.put("pos", intList(block.pos().getX() - selected.origin().getX(), block.pos().getY() - selected.origin().getY(), block.pos().getZ() - selected.origin().getZ()));
			blockNbt.putInt("state", stateIndex);
			blocks.add(blockNbt);
		}

		root.put("palette", palette);
		root.put("blocks", blocks);
		root.put("entities", new NbtList());

		ByteArrayOutputStream output = new ByteArrayOutputStream();
		NbtIo.writeCompressed(root, output);
		return output.toByteArray();
	}

	private static NbtList intList(int x, int y, int z) {
		NbtList list = new NbtList();
		list.add(NbtInt.of(x));
		list.add(NbtInt.of(y));
		list.add(NbtInt.of(z));
		return list;
	}

	private static void upload(ServerPlayerEntity player, String name, byte[] nbt, int blockCount) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			player.sendMessage(Text.literal("Capture upload failed: config is not loaded."), false);
			return;
		}

		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/structures/capture"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(15))
			.header("content-type", "application/octet-stream")
			.header("x-structure-name", safeHeader(name))
			.header("x-original-name", safeHeader(name + ".nbt"))
			.header("x-structure-category", "land")
			.header("x-placement-preset", PlacementPreset.SURFACE_HOUSE.apiName())
			.header("x-creator-name", safeHeader(player.getGameProfile().getName()))
			.header("x-creator-id", player.getUuidAsString())
			.POST(HttpRequest.BodyPublishers.ofByteArray(nbt))
			.build();

		MinecraftServer server = player.getServer();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> server.execute(() -> {
				if (throwable != null) {
					CommunityStructures.LOGGER.warn("Could not upload captured structure", throwable);
					player.sendMessage(Text.literal("Capture upload failed: " + throwable.getMessage()), false);
					return;
				}
				if (response.statusCode() / 100 != 2) {
					player.sendMessage(Text.literal("Capture upload failed: server returned HTTP " + response.statusCode() + " " + errorFromBody(response.body())), false);
					return;
				}
				CommunityStructures.LOGGER.info("Uploaded in-game captured structure {} with {} blocks", name, blockCount);
				player.sendMessage(Text.literal("Uploaded " + name + " with " + blockCount + " blocks. It is now in the database."), false);
				if (CommunityStructures.cache() != null) {
					CommunityStructures.cache().prefetchSoon();
				}
			}));
	}

	private static URI apiUri(CommunityStructureConfig config, String path) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		return URI.create(base + path);
	}

	private static String safeHeader(String value) {
		String safe = value == null ? "" : value.replaceAll("[^A-Za-z0-9 ._()-]", "").trim();
		return safe.isBlank() ? "captured-structure" : safe;
	}

	private static String captureName(ServerPlayerEntity player) {
		return safeHeader("capture-" + player.getGameProfile().getName() + "-" + NAME_TIME.format(Instant.now()));
	}

	private static String errorFromBody(String body) {
		try {
			ErrorResponse error = GSON.fromJson(body, ErrorResponse.class);
			if (error != null && error.error != null && !error.error.isBlank()) {
				return error.error;
			}
		} catch (RuntimeException ignored) {
		}
		return body == null || body.isBlank() ? "" : body;
	}

	private static void sendPreview(ServerPlayerEntity player, CapturePreview preview) {
		if (ServerPlayNetworking.canSend(player, CommunityStructureCapturePackets.CapturePreviewPayload.ID)) {
			ServerPlayNetworking.send(player, new CommunityStructureCapturePackets.CapturePreviewPayload(preview.active(), preview.min(), preview.max(), preview.selectedBlocks(), preview.blockPositions()));
		}
	}

	private record BlockSample(BlockPos pos, BlockState before) {
		private BlockSample {
			pos = pos.toImmutable();
		}
	}

	private record PendingPlacement(net.minecraft.registry.RegistryKey<World> worldKey, Block placedBlock, List<BlockSample> samples) {
	}

	private record ActiveCapture(int minX, int floorY, int minZ, int maxX, int maxZ) {
		private static ActiveCapture around(ServerPlayerEntity player) {
			BlockPos floor = player.getBlockPos().down();
			return new ActiveCapture(
				floor.getX() - SELECTION_NEGATIVE_RADIUS,
				floor.getY(),
				floor.getZ() - SELECTION_NEGATIVE_RADIUS,
				floor.getX() + SELECTION_POSITIVE_RADIUS,
				floor.getZ() + SELECTION_POSITIVE_RADIUS
			);
		}

		private BlockPos min() {
			return new BlockPos(minX, floorY, minZ);
		}

		private BlockPos max() {
			return new BlockPos(maxX, maxY(), maxZ);
		}

		private int maxY() {
			return floorY + SELECTION_HEIGHT - 1;
		}

		private CapturePreview preview(CapturedStructure selected) {
			List<BlockPos> positions = selected.blocks().stream()
				.map(CapturedBlock::pos)
				.toList();
			return new CapturePreview(true, min(), max(), positions.size(), positions);
		}
	}

	private record CapturedBlock(BlockPos pos, BlockState state) {
	}

	private record NonVanillaBlock(BlockPos pos, Identifier id) {
	}

	private record CapturedStructure(BlockPos origin, BlockPos max, List<CapturedBlock> blocks) {
		private int sizeX() {
			return Math.max(1, max.getX() - origin.getX() + 1);
		}

		private int sizeY() {
			return Math.max(1, max.getY() - origin.getY() + 1);
		}

		private int sizeZ() {
			return Math.max(1, max.getZ() - origin.getZ() + 1);
		}
	}

	private record CapturePreview(boolean active, BlockPos min, BlockPos max, int selectedBlocks, List<BlockPos> blockPositions) {
		private static CapturePreview inactive() {
			return new CapturePreview(false, BlockPos.ORIGIN, BlockPos.ORIGIN, 0, List.of());
		}
	}

	private record NbtCompoundKey(String value) {
	}

	private static final class ErrorResponse {
		private String error;
	}
}
