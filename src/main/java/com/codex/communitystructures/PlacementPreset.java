package com.codex.communitystructures;

import java.util.Locale;
import java.util.Optional;

public enum PlacementPreset {
	SURFACE_HOUSE("surface_structure"),
	SURFACE_RUIN("surface_ruin"),
	BURIED_RUIN("buried_ruin"),
	OCEAN_FLOOR("ocean_floor"),
	CAVE_ROOM("cave_room");

	private final String apiName;

	PlacementPreset(String apiName) {
		this.apiName = apiName;
	}

	public String apiName() {
		return apiName;
	}

	public int landExtraSink() {
		return switch (this) {
			case BURIED_RUIN -> 6;
			case SURFACE_RUIN -> 3;
			default -> 0;
		};
	}

	public int landSlopeLimit(CommunityStructureConfig config) {
		return switch (this) {
			case SURFACE_HOUSE -> Math.max(4, config.landMaxSlope);
			case SURFACE_RUIN -> Math.max(config.landMaxSlope, 18);
			case BURIED_RUIN -> Math.max(config.landMaxSlope, 24);
			default -> config.landMaxSlope;
		};
	}

	public boolean shouldBlendLandTerrain() {
		return this == SURFACE_HOUSE || this == SURFACE_RUIN || this == BURIED_RUIN;
	}

	public boolean shouldClearLandVegetation() {
		return this == SURFACE_HOUSE || this == SURFACE_RUIN || this == BURIED_RUIN;
	}

	public static PlacementPreset defaultFor(StructureCategory category) {
		return switch (category) {
			case WATER -> OCEAN_FLOOR;
			case CAVE -> CAVE_ROOM;
			case LAND -> SURFACE_HOUSE;
		};
	}

	public static PlacementPreset fromApiName(String apiName, StructureCategory category) {
		return optional(apiName).orElse(defaultFor(category));
	}

	private static Optional<PlacementPreset> optional(String apiName) {
		if (apiName == null || apiName.isBlank()) {
			return Optional.empty();
		}
		String normalized = apiName.trim().toLowerCase(Locale.ROOT).replace('-', '_');
		if (normalized.equals("surface_house")) {
			return Optional.of(SURFACE_HOUSE);
		}
		for (PlacementPreset preset : values()) {
			if (preset.apiName.equals(normalized)) {
				return Optional.of(preset);
			}
		}
		return Optional.empty();
	}
}
