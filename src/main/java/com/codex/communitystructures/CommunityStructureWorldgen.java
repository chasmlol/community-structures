package com.codex.communitystructures;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.structure.StructurePieceType;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.structure.StructureType;

public final class CommunityStructureWorldgen {
	public static StructurePieceType PIECE_TYPE;
	public static StructureType<CommunityConfiguredStructure> STRUCTURE_TYPE;

	private CommunityStructureWorldgen() {
	}

	public static void register() {
		PIECE_TYPE = Registry.register(
			Registries.STRUCTURE_PIECE,
			Identifier.of(CommunityStructures.MOD_ID, "dynamic_piece"),
			(context, nbt) -> new CommunityStructurePiece(nbt)
		);
		STRUCTURE_TYPE = Registry.register(
			Registries.STRUCTURE_TYPE,
			Identifier.of(CommunityStructures.MOD_ID, "dynamic_structure"),
			() -> CommunityConfiguredStructure.CODEC
		);
	}
}
