package com.codex.communitystructures;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class CommunityStructureInstanceState extends PersistentState {
	private static final String STATE_ID = CommunityStructures.MOD_ID + "_generated_instances";
	private static final Type<CommunityStructureInstanceState> TYPE = new Type<>(
		CommunityStructureInstanceState::new,
		CommunityStructureInstanceState::fromNbt,
		DataFixTypes.LEVEL
	);

	private final Map<String, Instance> instances = new HashMap<>();

	public static CommunityStructureInstanceState forWorld(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, STATE_ID);
	}

	private static CommunityStructureInstanceState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		CommunityStructureInstanceState state = new CommunityStructureInstanceState();
		NbtList instanceList = nbt.getList("instances", NbtElement.COMPOUND_TYPE);
		for (int index = 0; index < instanceList.size(); index++) {
			Instance instance = Instance.fromNbt(instanceList.getCompound(index));
			if (instance != null) {
				state.instances.put(instance.instanceId(), instance);
			}
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		NbtList instanceList = new NbtList();
		for (Instance instance : instances.values()) {
			instanceList.add(instance.toNbt());
		}
		nbt.put("instances", instanceList);
		return nbt;
	}

	public void remember(String instanceId, CachedStructure structure, Collection<BlockPos> blockPositions) {
		if (instanceId == null || instanceId.isBlank() || instances.containsKey(instanceId) || !structure.hasCreator() || blockPositions.isEmpty()) {
			return;
		}

		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		long[] blocks = new long[blockPositions.size()];
		int index = 0;
		for (BlockPos pos : blockPositions) {
			minX = Math.min(minX, pos.getX());
			minY = Math.min(minY, pos.getY());
			minZ = Math.min(minZ, pos.getZ());
			maxX = Math.max(maxX, pos.getX());
			maxY = Math.max(maxY, pos.getY());
			maxZ = Math.max(maxZ, pos.getZ());
			blocks[index++] = pos.asLong();
		}

		instances.put(instanceId, new Instance(
			instanceId,
			structure.id(),
			structure.name(),
			structure.creatorId(),
			structure.creatorName(),
			minX,
			minY,
			minZ,
			maxX,
			maxY,
			maxZ,
			blocks
		));
		markDirty();
	}

	public Optional<Instance> nearby(BlockPos pos, double radius) {
		double radiusSquared = radius * radius;
		int intRadius = (int) Math.ceil(radius);
		for (Instance instance : instances.values()) {
			if (!instance.containsExpanded(pos, intRadius)) {
				continue;
			}
			if (instance.isNearBlock(pos, radiusSquared)) {
				return Optional.of(instance);
			}
		}
		return Optional.empty();
	}

	public Optional<Instance> nearby(String structureId, String creatorId, BlockPos pos, double radius) {
		double radiusSquared = radius * radius;
		int intRadius = (int) Math.ceil(radius);
		for (Instance instance : instances.values()) {
			if (!instance.structureId().equals(structureId) || !instance.creatorId().equals(creatorId)) {
				continue;
			}
			if (!instance.containsExpanded(pos, intRadius)) {
				continue;
			}
			if (instance.isNearBlock(pos, radiusSquared)) {
				return Optional.of(instance);
			}
		}
		return Optional.empty();
	}

	public record Instance(
		String instanceId,
		String structureId,
		String structureName,
		String creatorId,
		String creatorName,
		int minX,
		int minY,
		int minZ,
		int maxX,
		int maxY,
		int maxZ,
		long[] blocks
	) {
		private boolean containsExpanded(BlockPos pos, int radius) {
			return pos.getX() >= minX - radius
				&& pos.getX() <= maxX + radius
				&& pos.getY() >= minY - radius
				&& pos.getY() <= maxY + radius
				&& pos.getZ() >= minZ - radius
				&& pos.getZ() <= maxZ + radius;
		}

		private boolean isNearBlock(BlockPos playerPos, double radiusSquared) {
			double playerX = playerPos.getX() + 0.5D;
			double playerY = playerPos.getY() + 0.5D;
			double playerZ = playerPos.getZ() + 0.5D;
			for (long packed : blocks) {
				BlockPos block = BlockPos.fromLong(packed);
				double dx = block.getX() + 0.5D - playerX;
				double dy = block.getY() + 0.5D - playerY;
				double dz = block.getZ() + 0.5D - playerZ;
				if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
					return true;
				}
			}
			return false;
		}

		private NbtCompound toNbt() {
			NbtCompound nbt = new NbtCompound();
			nbt.putString("instanceId", instanceId);
			nbt.putString("structureId", structureId);
			nbt.putString("structureName", structureName);
			nbt.putString("creatorId", creatorId);
			nbt.putString("creatorName", creatorName);
			nbt.putInt("minX", minX);
			nbt.putInt("minY", minY);
			nbt.putInt("minZ", minZ);
			nbt.putInt("maxX", maxX);
			nbt.putInt("maxY", maxY);
			nbt.putInt("maxZ", maxZ);
			nbt.putLongArray("blocks", blocks);
			return nbt;
		}

		private static Instance fromNbt(NbtCompound nbt) {
			String instanceId = nbt.getString("instanceId");
			String structureId = nbt.getString("structureId");
			String creatorId = nbt.getString("creatorId");
			String creatorName = nbt.getString("creatorName");
			long[] blocks = nbt.getLongArray("blocks");
			if (instanceId.isBlank() || structureId.isBlank() || creatorId.isBlank() || creatorName.isBlank() || blocks.length == 0) {
				return null;
			}
			return new Instance(
				instanceId,
				structureId,
				nbt.getString("structureName"),
				creatorId,
				creatorName,
				nbt.getInt("minX"),
				nbt.getInt("minY"),
				nbt.getInt("minZ"),
				nbt.getInt("maxX"),
				nbt.getInt("maxY"),
				nbt.getInt("maxZ"),
				blocks
			);
		}
	}
}
