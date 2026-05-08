package com.codex.communitystructures;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.loot.LootTable;
import net.minecraft.loot.LootTables;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

final class CommunityStructureBlockEntities {
	static final int MAX_LOOT_CONTAINERS = 2;

	private static final List<RegistryKey<LootTable>> VILLAGE_LOOT_TABLES = List.of(
		LootTables.VILLAGE_WEAPONSMITH_CHEST,
		LootTables.VILLAGE_TOOLSMITH_CHEST,
		LootTables.VILLAGE_ARMORER_CHEST,
		LootTables.VILLAGE_CARTOGRAPHER_CHEST,
		LootTables.VILLAGE_MASON_CHEST,
		LootTables.VILLAGE_SHEPARD_CHEST,
		LootTables.VILLAGE_BUTCHER_CHEST,
		LootTables.VILLAGE_FLETCHER_CHEST,
		LootTables.VILLAGE_FISHER_CHEST,
		LootTables.VILLAGE_TANNERY_CHEST,
		LootTables.VILLAGE_TEMPLE_CHEST,
		LootTables.VILLAGE_DESERT_HOUSE_CHEST,
		LootTables.VILLAGE_PLAINS_CHEST,
		LootTables.VILLAGE_TAIGA_HOUSE_CHEST,
		LootTables.VILLAGE_SNOWY_HOUSE_CHEST,
		LootTables.VILLAGE_SAVANNA_HOUSE_CHEST
	);

	private CommunityStructureBlockEntities() {
	}

	static boolean isLootableContainer(ServerWorld world, BlockPos pos) {
		return world.getBlockEntity(pos) instanceof LootableContainerBlockEntity;
	}

	static boolean isLikelyLootContainerState(BlockState state) {
		Block block = state.getBlock();
		return block == Blocks.CHEST
			|| block == Blocks.TRAPPED_CHEST
			|| block == Blocks.BARREL
			|| block == Blocks.SHULKER_BOX
			|| block == Blocks.WHITE_SHULKER_BOX
			|| block == Blocks.ORANGE_SHULKER_BOX
			|| block == Blocks.MAGENTA_SHULKER_BOX
			|| block == Blocks.LIGHT_BLUE_SHULKER_BOX
			|| block == Blocks.YELLOW_SHULKER_BOX
			|| block == Blocks.LIME_SHULKER_BOX
			|| block == Blocks.PINK_SHULKER_BOX
			|| block == Blocks.GRAY_SHULKER_BOX
			|| block == Blocks.LIGHT_GRAY_SHULKER_BOX
			|| block == Blocks.CYAN_SHULKER_BOX
			|| block == Blocks.PURPLE_SHULKER_BOX
			|| block == Blocks.BLUE_SHULKER_BOX
			|| block == Blocks.BROWN_SHULKER_BOX
			|| block == Blocks.GREEN_SHULKER_BOX
			|| block == Blocks.RED_SHULKER_BOX
			|| block == Blocks.BLACK_SHULKER_BOX;
	}

	static NbtCompound capturedBlockEntityNbt(ServerWorld world, BlockPos pos) {
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity == null || blockEntity instanceof LootableContainerBlockEntity) {
			return null;
		}
		NbtCompound nbt = blockEntity.createNbtWithId(world.getRegistryManager());
		sanitizeCopiedBlockEntityNbt(nbt);
		return nbt.isEmpty() ? null : nbt;
	}

	static void applyPlacedBlockEntity(ServerWorld world, BlockPos pos, NbtCompound sourceNbt, boolean giveVillageLoot, long seed) {
		BlockEntity blockEntity = world.getBlockEntity(pos);
		if (blockEntity == null) {
			return;
		}
		if (blockEntity instanceof LootableContainerBlockEntity lootable) {
			if (giveVillageLoot) {
				lootable.setLootTable(villageLootTable(seed));
				lootable.setLootTableSeed(mixSeed(seed, pos.asLong()));
				blockEntity.markDirty();
			}
			return;
		}
		if (sourceNbt == null || sourceNbt.isEmpty()) {
			return;
		}

		NbtCompound nbt = sourceNbt.copy();
		sanitizeCopiedBlockEntityNbt(nbt);
		nbt.putInt("x", pos.getX());
		nbt.putInt("y", pos.getY());
		nbt.putInt("z", pos.getZ());
		blockEntity.read(nbt, world.getRegistryManager());
		blockEntity.markDirty();
	}

	private static RegistryKey<LootTable> villageLootTable(long seed) {
		int index = (int) Math.floorMod(mixSeed(seed, 0x63af8f71d56c31b7L), VILLAGE_LOOT_TABLES.size());
		return VILLAGE_LOOT_TABLES.get(index);
	}

	private static long mixSeed(long seed, long salt) {
		long mixed = seed ^ salt;
		mixed ^= mixed >>> 33;
		mixed *= 0xff51afd7ed558ccdL;
		mixed ^= mixed >>> 33;
		mixed *= 0xc4ceb9fe1a85ec53L;
		mixed ^= mixed >>> 33;
		return mixed;
	}

	private static void sanitizeCopiedBlockEntityNbt(NbtCompound nbt) {
		nbt.remove("Items");
		nbt.remove("LootTable");
		nbt.remove("LootTableSeed");
		nbt.remove("components");
	}
}
