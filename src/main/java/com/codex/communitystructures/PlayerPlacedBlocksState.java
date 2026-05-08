package com.codex.communitystructures;

import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

import java.util.HashSet;
import java.util.Set;

public final class PlayerPlacedBlocksState extends PersistentState {
	private static final String STATE_ID = CommunityStructures.MOD_ID + "_player_placed_blocks";
	private static final Type<PlayerPlacedBlocksState> TYPE = new Type<>(
		PlayerPlacedBlocksState::new,
		PlayerPlacedBlocksState::fromNbt,
		DataFixTypes.LEVEL
	);

	private final Set<Long> positions = new HashSet<>();

	public static PlayerPlacedBlocksState forWorld(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, STATE_ID);
	}

	private static PlayerPlacedBlocksState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		PlayerPlacedBlocksState state = new PlayerPlacedBlocksState();
		for (long position : nbt.getLongArray("positions")) {
			state.positions.add(position);
		}
		return state;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
		long[] savedPositions = new long[positions.size()];
		int index = 0;
		for (long position : positions) {
			savedPositions[index++] = position;
		}
		nbt.putLongArray("positions", savedPositions);
		return nbt;
	}

	public boolean contains(BlockPos pos) {
		return positions.contains(pos.asLong());
	}

	public void remember(BlockPos pos) {
		if (positions.add(pos.asLong())) {
			markDirty();
		}
	}

	public void forget(BlockPos pos) {
		if (positions.remove(pos.asLong())) {
			markDirty();
		}
	}
}
