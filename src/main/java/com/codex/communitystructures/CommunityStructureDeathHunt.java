package com.codex.communitystructures;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
import net.minecraft.loot.context.LootContextParameterSet;
import net.minecraft.loot.context.LootContextParameters;
import net.minecraft.loot.context.LootContextTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CommunityStructureDeathHunt {
	private static final Gson GSON = new Gson();
	private static final long LOCAL_HUNT_MILLIS = 5 * 60 * 1000L;
	private static final double DROP_CLEANUP_RADIUS = 8.0D;
	private static final int MIN_HUNT_SPAWN_DISTANCE = 18;
	private static final int MAX_HUNT_SPAWN_DISTANCE = 32;
	private static final int HUNT_SPAWN_ATTEMPTS = 48;
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(4))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private static final Map<UUID, PendingDeath> PENDING_DEATHS = new ConcurrentHashMap<>();
	private static final Map<String, ActiveHunt> ACTIVE_HUNTS = new ConcurrentHashMap<>();

	private CommunityStructureDeathHunt() {
	}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DEATH.register(CommunityStructureDeathHunt::beforeDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(CommunityStructureDeathHunt::afterDeath);
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.register(CommunityStructureDeathHunt::afterKilledOtherEntity);
		ServerTickEvents.END_SERVER_TICK.register(CommunityStructureDeathHunt::tick);
	}

	private static boolean beforeDeath(net.minecraft.entity.LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof ServerPlayerEntity player)) {
			return true;
		}
		if (player.getWorld().getGameRules().getBoolean(GameRules.KEEP_INVENTORY)) {
			return true;
		}

		PendingDeath death = captureDeath(player);
		if (!death.items().isEmpty()) {
			PENDING_DEATHS.put(player.getUuid(), death);
		}
		return true;
	}

	private static void afterDeath(net.minecraft.entity.LivingEntity entity, DamageSource source) {
		ActiveHunt activeHunt = activeHuntFor(entity.getUuid());
		if (activeHunt != null) {
			ACTIVE_HUNTS.remove(activeHunt.id());
			dropRecoveryReward(entity);
			MinecraftServer server = entity.getServer();
			if (server != null) {
				ServerPlayerEntity hunter = playerById(server, activeHunt.assignedPlayerId());
				if (hunter != null) {
					CommunityStructureChat.activateDeathHuntRoom(hunter, activeHunt.id(), activeHunt.deadPlayerId(), activeHunt.deadPlayerName(), activeHunt.assignedPlayerId(), activeHunt.assignedPlayerName(), System.currentTimeMillis() + 60_000L);
				}
				completeHunt(server, activeHunt.id(), activeHunt.assignedPlayerId(), activeHunt.assignedPlayerName(), hunter);
			}
			return;
		}

		if (!(entity instanceof ServerPlayerEntity player)) {
			return;
		}
		PendingDeath death = PENDING_DEATHS.remove(player.getUuid());
		if (death == null || death.items().isEmpty()) {
			return;
		}
		postDeath(player.getServer(), death);
	}

	private static PendingDeath captureDeath(ServerPlayerEntity player) {
		List<SavedItem> items = new ArrayList<>();
		PlayerInventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			ItemStack stack = inventory.getStack(slot);
			if (!stack.isEmpty()) {
				items.add(savedItem(player, slot, stack));
			}
		}

		BlockPos pos = player.getBlockPos();
		return new PendingDeath(
			player.getUuidAsString(),
			player.getGameProfile().getName(),
			pos.getX(),
			pos.getY(),
			pos.getZ(),
			player.getServerWorld().getRegistryKey().getValue().toString(),
			inventory.selectedSlot,
			player.totalExperience,
			player.experienceLevel,
			player.experienceProgress,
			items
		);
	}

	private static SavedItem savedItem(ServerPlayerEntity player, int slot, ItemStack stack) {
		NbtElement encoded = stack.encode(player.getRegistryManager());
		Identifier itemId = Registries.ITEM.getId(stack.getItem());
		return new SavedItem(slot, itemId.toString(), stack.getCount(), stack.getName().getString(), encoded.toString());
	}

	private static void postDeath(MinecraftServer server, PendingDeath death) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/death-hunts"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(10))
			.header("content-type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(death), StandardCharsets.UTF_8))
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> server.execute(() -> {
				ServerPlayerEntity player = server.getPlayerManager().getPlayer(UUID.fromString(death.deadPlayerId()));
				if (throwable != null) {
					CommunityStructures.LOGGER.warn("Could not post death hunt", throwable);
					if (player != null) {
						player.sendMessage(Text.literal("Death hunt could not be sent to the server."), false);
					}
					return;
				}
				if (response.statusCode() / 100 != 2) {
					CommunityStructures.LOGGER.warn("Death hunt endpoint returned HTTP {}: {}", response.statusCode(), response.body());
					if (player != null) {
						player.sendMessage(Text.literal("Death hunt was rejected by the server."), false);
					}
					return;
				}
				DeathPostResponse body = parse(response.body(), DeathPostResponse.class);
				if (player != null) {
					if (body != null && body.assigned) {
						player.sendMessage(Text.literal("A recovery zombie was sent to another player. If they kill it in time, your items will return."), false);
					} else {
						player.sendMessage(Text.literal("No other modded player is online yet. Your recovery zombie will be assigned when someone connects."), false);
					}
				}
			}));
	}

	public static void receiveDeathHunts(ServerPlayerEntity player, List<IncomingDeathHunt> hunts) {
		for (IncomingDeathHunt hunt : hunts) {
			spawnDeathHunt(player, hunt);
		}
	}

	private static void spawnDeathHunt(ServerPlayerEntity player, IncomingDeathHunt hunt) {
		if (hunt == null || hunt.id == null || hunt.id.isBlank() || ACTIVE_HUNTS.containsKey(hunt.id)) {
			return;
		}
		if (hunt.expiresAt != null && !hunt.expiresAt.isBlank()) {
			try {
				if (Instant.parse(hunt.expiresAt).toEpochMilli() <= System.currentTimeMillis()) {
					return;
				}
			} catch (RuntimeException ignored) {
			}
		}

		ServerWorld world = player.getServerWorld();
		BlockPos spawnPos = spawnPosNear(player);
		ZombieEntity zombie = new ZombieEntity(EntityType.ZOMBIE, world);
		zombie.refreshPositionAndAngles(spawnPos, player.getYaw() + 180.0F, 0.0F);
		zombie.setCustomName(Text.literal(hunt.deadPlayerName + "'s lost gear"));
		zombie.setCustomNameVisible(true);
		zombie.setPersistent();
		zombie.setCanPickUpLoot(false);
		equipDeathGear(player, zombie, hunt);
		zombie.equipStack(EquipmentSlot.HEAD, playerHead(hunt.deadPlayerId, hunt.deadPlayerName));
		zombie.setEquipmentDropChance(EquipmentSlot.HEAD, 0.0F);
		zombie.addCommandTag("community_structures_death_hunt");
		if (world.spawnEntity(zombie)) {
			long expiresAt = parseExpiry(hunt.expiresAt);
			String assignedPlayerId = hunt.assignedToId == null || hunt.assignedToId.isBlank() ? player.getUuidAsString() : hunt.assignedToId;
			String assignedPlayerName = hunt.assignedToName == null || hunt.assignedToName.isBlank() ? player.getGameProfile().getName() : hunt.assignedToName;
			ACTIVE_HUNTS.put(hunt.id, new ActiveHunt(hunt.id, zombie.getUuid(), world.getRegistryKey(), expiresAt, assignedPlayerId, assignedPlayerName, hunt.deadPlayerId, hunt.deadPlayerName));
			CommunityStructureChat.activateDeathHuntRoom(player, hunt.id, hunt.deadPlayerId, hunt.deadPlayerName, assignedPlayerId, assignedPlayerName, expiresAt);
			player.sendMessage(Text.empty()
				.append(Text.literal(safeName(hunt.deadPlayerName)).formatted(Formatting.GOLD, Formatting.BOLD))
				.append(Text.literal(" has died. Hunt his zombie corpse to return his items.")), false);
		}
	}

	private static BlockPos spawnPosNear(ServerPlayerEntity player) {
		ServerWorld world = player.getServerWorld();
		BlockPos center = player.getBlockPos();
		for (int attempt = 0; attempt < HUNT_SPAWN_ATTEMPTS; attempt++) {
			double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
			int distance = MIN_HUNT_SPAWN_DISTANCE + player.getRandom().nextInt(MAX_HUNT_SPAWN_DISTANCE - MIN_HUNT_SPAWN_DISTANCE + 1);
			int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
			int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
			BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, center.getY(), z));
			Optional<BlockPos> safe = safeSpawnAtOrNear(world, top);
			if (safe.isPresent()) {
				return safe.get();
			}
		}

		Direction facing = player.getHorizontalFacing();
		BlockPos fallback = center.offset(facing, MIN_HUNT_SPAWN_DISTANCE);
		for (int dy = 8; dy >= -8; dy--) {
			BlockPos pos = fallback.up(dy);
			if (isSafeZombieSpawn(world, pos)) {
				return pos;
			}
		}
		return center;
	}

	private static Optional<BlockPos> safeSpawnAtOrNear(ServerWorld world, BlockPos top) {
		for (int dy = 2; dy >= -4; dy--) {
			BlockPos pos = top.up(dy);
			if (isSafeZombieSpawn(world, pos)) {
				return Optional.of(pos);
			}
		}
		return Optional.empty();
	}

	private static boolean isSafeZombieSpawn(ServerWorld world, BlockPos pos) {
		if (pos.getY() <= world.getBottomY() || pos.getY() + 1 >= world.getTopY()) {
			return false;
		}
		BlockState below = world.getBlockState(pos.down());
		BlockState feet = world.getBlockState(pos);
		BlockState head = world.getBlockState(pos.up());
		return below.isSideSolidFullSquare(world, pos.down(), Direction.UP)
			&& below.getFluidState().isEmpty()
			&& feet.getCollisionShape(world, pos).isEmpty()
			&& head.getCollisionShape(world, pos.up()).isEmpty()
			&& feet.getFluidState().isEmpty()
			&& head.getFluidState().isEmpty();
	}

	private static ItemStack playerHead(String playerId, String playerName) {
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		try {
			UUID id = UUID.fromString(playerId);
			head.set(DataComponentTypes.PROFILE, new ProfileComponent(new GameProfile(id, safeName(playerName))));
		} catch (IllegalArgumentException ignored) {
		}
		return head;
	}

	private static void equipDeathGear(ServerPlayerEntity player, ZombieEntity zombie, IncomingDeathHunt hunt) {
		if (hunt.equipment == null || hunt.equipment.isEmpty()) {
			return;
		}
		for (IncomingDeathEquipment item : hunt.equipment) {
			EquipmentSlot slot = equipmentSlot(item.slot);
			if (slot == null || slot == EquipmentSlot.HEAD) {
				continue;
			}
			ItemStack stack = decodeStack(player, item.itemId, item.stackNbt);
			if (stack.isEmpty()) {
				continue;
			}
			zombie.equipStack(slot, stack.copy());
			zombie.setEquipmentDropChance(slot, 0.0F);
		}
	}

	private static EquipmentSlot equipmentSlot(String slot) {
		return switch (slot == null ? "" : slot) {
			case "mainhand" -> EquipmentSlot.MAINHAND;
			case "offhand" -> EquipmentSlot.OFFHAND;
			case "feet" -> EquipmentSlot.FEET;
			case "legs" -> EquipmentSlot.LEGS;
			case "chest" -> EquipmentSlot.CHEST;
			case "head" -> EquipmentSlot.HEAD;
			default -> null;
		};
	}

	private static void afterKilledOtherEntity(ServerWorld world, Entity killer, net.minecraft.entity.LivingEntity killedEntity) {
		if (!(killer instanceof ServerPlayerEntity player)) {
			return;
		}
		ActiveHunt hunt = activeHuntFor(killedEntity.getUuid());
		if (hunt == null) {
			return;
		}
		ACTIVE_HUNTS.remove(hunt.id());
		dropRecoveryReward(killedEntity);
		CommunityStructureChat.activateDeathHuntRoom(player, hunt.id(), hunt.deadPlayerId(), hunt.deadPlayerName(), hunt.assignedPlayerId(), hunt.assignedPlayerName(), System.currentTimeMillis() + 60_000L);
		completeHunt(player.getServer(), hunt.id(), hunt.assignedPlayerId(), hunt.assignedPlayerName(), player);
	}

	private static void dropRecoveryReward(net.minecraft.entity.LivingEntity entity) {
		if (!(entity.getWorld() instanceof ServerWorld world) || world.getServer() == null) {
			return;
		}
		Random random = entity.getRandom();
		RegistryKey<LootTable> tableKey = random.nextBoolean()
			? LootTables.END_CITY_TREASURE_CHEST
			: LootTables.VILLAGE_WEAPONSMITH_CHEST;
		LootTable table = world.getServer().getReloadableRegistries().getLootTable(tableKey);
		LootContextParameterSet parameters = new LootContextParameterSet.Builder(world)
			.add(LootContextParameters.ORIGIN, entity.getPos())
			.build(LootContextTypes.CHEST);
		List<ItemStack> stacks = new ArrayList<>(table.generateLoot(parameters, random));
		stacks.removeIf(ItemStack::isEmpty);
		if (stacks.isEmpty()) {
			return;
		}

		for (int i = stacks.size() - 1; i > 0; i--) {
			int swapIndex = random.nextInt(i + 1);
			ItemStack stack = stacks.get(i);
			stacks.set(i, stacks.get(swapIndex));
			stacks.set(swapIndex, stack);
		}
		int keep = Math.max(1, (stacks.size() + 1) / 2);
		for (int i = 0; i < keep; i++) {
			entity.dropStack(stacks.get(i));
		}
	}

	private static ActiveHunt activeHuntFor(UUID zombieId) {
		for (ActiveHunt hunt : ACTIVE_HUNTS.values()) {
			if (hunt.zombieId().equals(zombieId)) {
				return hunt;
			}
		}
		return null;
	}

	private static void completeHunt(MinecraftServer server, String huntId, String hunterId, String hunterName, ServerPlayerEntity notifyPlayer) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		CompleteDeathHunt payload = new CompleteDeathHunt(hunterId, hunterName);
		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/death-hunts/" + huntId + "/complete"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.header("content-type", "application/json; charset=utf-8")
			.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
			.whenComplete((response, throwable) -> server.execute(() -> {
				if (notifyPlayer == null) {
					if (throwable != null || response == null || response.statusCode() / 100 != 2) {
						CommunityStructures.LOGGER.warn("The recovery zombie died, but the server could not confirm hunt {}", huntId);
					}
					return;
				}
				if (throwable != null || response == null || response.statusCode() / 100 != 2) {
					notifyPlayer.sendMessage(Text.literal("The recovery zombie died, but the server could not confirm the return."), false);
					return;
				}
				notifyPlayer.sendMessage(Text.literal("The recovery zombie died. Their gear is being returned."), false);
			}));
	}

	public static void receiveDeathReturns(ServerPlayerEntity player, List<IncomingDeathReturn> returns) {
		for (IncomingDeathReturn deathReturn : returns) {
			restoreDeathReturn(player, deathReturn);
		}
	}

	private static void restoreDeathReturn(ServerPlayerEntity player, IncomingDeathReturn deathReturn) {
		if (deathReturn == null || deathReturn.id == null || deathReturn.id.isBlank()) {
			return;
		}
		if (deathReturn.deadPlayerId == null || !deathReturn.deadPlayerId.equals(player.getUuidAsString())) {
			return;
		}

		List<RestoredItem> restored = new ArrayList<>();
		if (deathReturn.items != null) {
			for (IncomingDeathItem item : deathReturn.items) {
				ItemStack stack = decodeStack(player, item);
				if (!stack.isEmpty()) {
					restored.add(new RestoredItem(Math.max(0, item.slot), stack));
				}
			}
		}
		if (restored.isEmpty()) {
			return;
		}

		removeDeathDrops(player, deathReturn, restored);
		restoreInventory(player, restored, deathReturn.selectedSlot);
		CommunityStructureChat.activateDeathHuntRoom(player, deathReturn.id, deathReturn.deadPlayerId, deathReturn.deadPlayerName, deathReturn.hunterId, deathReturn.hunterName, System.currentTimeMillis() + 60_000L);
		player.totalExperience = Math.max(0, deathReturn.totalExperience);
		player.experienceLevel = Math.max(0, deathReturn.experienceLevel);
		player.experienceProgress = Math.max(0.0F, Math.min(1.0F, deathReturn.experienceProgress));
		player.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
		claimDeathReturn(player.getServer(), deathReturn.id);
		player.sendMessage(Text.literal("Your death gear was recovered by " + safeName(deathReturn.hunterName) + "."), false);
	}

	private static void restoreInventory(ServerPlayerEntity player, List<RestoredItem> restored, int selectedSlot) {
		PlayerInventory inventory = player.getInventory();
		for (RestoredItem item : restored) {
			if (item.slot() >= inventory.size()) {
				inventory.offerOrDrop(item.stack().copy());
				continue;
			}
			ItemStack current = inventory.getStack(item.slot());
			if (!current.isEmpty()) {
				inventory.offerOrDrop(current.copy());
			}
			inventory.setStack(item.slot(), item.stack().copy());
		}
		inventory.selectedSlot = Math.max(0, Math.min(8, selectedSlot));
		inventory.markDirty();
		player.playerScreenHandler.sendContentUpdates();
		player.currentScreenHandler.sendContentUpdates();
	}

	private static void removeDeathDrops(ServerPlayerEntity player, IncomingDeathReturn deathReturn, List<RestoredItem> restored) {
		Optional<RegistryKey<World>> key = worldKey(deathReturn.dimension);
		ServerWorld world = key.flatMap(value -> Optional.ofNullable(player.getServer().getWorld(value))).orElse(player.getServerWorld());
		BlockPos deathPos = new BlockPos(deathReturn.deathX, deathReturn.deathY, deathReturn.deathZ);
		Box box = new Box(deathPos).expand(DROP_CLEANUP_RADIUS);
		Map<String, Integer> remaining = new HashMap<>();
		for (RestoredItem item : restored) {
			remaining.merge(stackKey(player, item.stack()), item.stack().getCount(), Integer::sum);
		}

		for (ItemEntity itemEntity : world.getEntitiesByType(TypeFilter.instanceOf(ItemEntity.class), box, entity -> true)) {
			ItemStack stack = itemEntity.getStack();
			String keyString = stackKey(player, stack);
			int needed = remaining.getOrDefault(keyString, 0);
			if (needed <= 0) {
				continue;
			}
			if (stack.getCount() <= needed) {
				remaining.put(keyString, needed - stack.getCount());
				itemEntity.discard();
			} else {
				stack.decrement(needed);
				remaining.remove(keyString);
			}
		}
		for (ExperienceOrbEntity orb : world.getEntitiesByType(TypeFilter.instanceOf(ExperienceOrbEntity.class), box, entity -> true)) {
			orb.discard();
		}
	}

	private static Optional<RegistryKey<World>> worldKey(String dimension) {
		Identifier id = Identifier.tryParse(dimension == null || dimension.isBlank() ? "minecraft:overworld" : dimension);
		if (id == null) {
			return Optional.empty();
		}
		return Optional.of(RegistryKey.of(RegistryKeys.WORLD, id));
	}

	private static String stackKey(ServerPlayerEntity player, ItemStack stack) {
		if (stack.isEmpty()) {
			return "empty";
		}
		return stack.copyWithCount(1).encode(player.getRegistryManager()).toString();
	}

	private static ItemStack decodeStack(ServerPlayerEntity player, IncomingDeathItem item) {
		if (item == null) {
			return ItemStack.EMPTY;
		}
		return decodeStack(player, item.itemId, item.stackNbt);
	}

	private static ItemStack decodeStack(ServerPlayerEntity player, String itemId, String stackNbt) {
		if (stackNbt == null || stackNbt.isBlank()) {
			return ItemStack.EMPTY;
		}
		try {
			NbtCompound nbt = StringNbtReader.parse(stackNbt);
			return ItemStack.fromNbt(player.getRegistryManager(), nbt).orElse(ItemStack.EMPTY);
		} catch (CommandSyntaxException | RuntimeException exception) {
			CommunityStructures.LOGGER.warn("Could not decode death hunt item {}", itemId, exception);
			return ItemStack.EMPTY;
		}
	}

	private static ServerPlayerEntity playerById(MinecraftServer server, String playerId) {
		if (server == null || playerId == null || playerId.isBlank()) {
			return null;
		}
		try {
			return server.getPlayerManager().getPlayer(UUID.fromString(playerId));
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static void claimDeathReturn(MinecraftServer server, String huntId) {
		CommunityStructureConfig config = CommunityStructures.config();
		if (config == null) {
			return;
		}
		HttpRequest request = HttpRequest.newBuilder(apiUri(config, "/api/death-hunts/" + huntId + "/claim"))
			.version(HttpClient.Version.HTTP_1_1)
			.timeout(Duration.ofSeconds(8))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();
		HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding())
			.whenComplete((response, throwable) -> {
				if (throwable != null) {
					CommunityStructures.LOGGER.debug("Could not claim death hunt {}", huntId, throwable);
				}
			});
	}

	private static void tick(MinecraftServer server) {
		long now = System.currentTimeMillis();
		for (Map.Entry<String, ActiveHunt> entry : ACTIVE_HUNTS.entrySet()) {
			ActiveHunt hunt = entry.getValue();
			if (hunt.expiresAtMillis() > now) {
				continue;
			}
			ServerWorld world = server.getWorld(hunt.worldKey());
			if (world != null) {
				Entity entity = world.getEntity(hunt.zombieId());
				if (entity != null) {
					entity.discard();
				}
			}
			ACTIVE_HUNTS.remove(entry.getKey(), hunt);
		}
	}

	private static long parseExpiry(String expiresAt) {
		if (expiresAt != null && !expiresAt.isBlank()) {
			try {
				return Instant.parse(expiresAt).toEpochMilli();
			} catch (RuntimeException ignored) {
			}
		}
		return System.currentTimeMillis() + LOCAL_HUNT_MILLIS;
	}

	private static <T> T parse(String body, Class<T> type) {
		try {
			return GSON.fromJson(body, type);
		} catch (RuntimeException ignored) {
			return null;
		}
	}

	private static URI apiUri(CommunityStructureConfig config, String path) {
		String base = config.apiBaseUrl.replaceAll("/+$", "");
		return URI.create(base + path);
	}

	private static String safeName(String value) {
		if (value == null || value.isBlank()) {
			return "Player";
		}
		return value.replaceAll("[\\r\\n<>]", "").trim();
	}

	private record PendingDeath(
		String deadPlayerId,
		String deadPlayerName,
		int deathX,
		int deathY,
		int deathZ,
		String dimension,
		int selectedSlot,
		int totalExperience,
		int experienceLevel,
		float experienceProgress,
		List<SavedItem> items
	) {
	}

	private record SavedItem(int slot, String itemId, int count, String name, String stackNbt) {
	}

	private record CompleteDeathHunt(String hunterId, String hunterName) {
	}

	private record ActiveHunt(String id, UUID zombieId, RegistryKey<World> worldKey, long expiresAtMillis, String assignedPlayerId, String assignedPlayerName, String deadPlayerId, String deadPlayerName) {
	}

	private record RestoredItem(int slot, ItemStack stack) {
	}

	private static final class DeathPostResponse {
		private boolean assigned;
	}

	public static final class IncomingDeathHunt {
		private String id;
		private String deadPlayerId;
		private String deadPlayerName;
		private String assignedToId;
		private String assignedToName;
		private int deathX;
		private int deathY;
		private int deathZ;
		private String dimension;
		private String expiresAt;
		private List<IncomingDeathEquipment> equipment;
	}

	public static final class IncomingDeathReturn {
		private String id;
		private String deadPlayerId;
		private String deadPlayerName;
		private String hunterId;
		private String hunterName;
		private int deathX;
		private int deathY;
		private int deathZ;
		private String dimension;
		private int selectedSlot;
		private int totalExperience;
		private int experienceLevel;
		private float experienceProgress;
		private List<IncomingDeathItem> items;
	}

	private static final class IncomingDeathItem {
		private int slot;
		private String itemId;
		private String stackNbt;
	}

	private static final class IncomingDeathEquipment {
		private String slot;
		private String itemId;
		private String stackNbt;
	}
}
