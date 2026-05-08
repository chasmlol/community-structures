package com.codex.communitystructures;

import java.nio.file.Path;
import java.util.Set;

public record CachedStructure(StructureCategory category, String id, String name, Path path, Set<String> allowedBiomes, PlacementPreset placementPreset, String creatorName, String creatorId) {
	public boolean canSpawnIn(String biomeId) {
		return allowedBiomes == null || allowedBiomes.isEmpty() || allowedBiomes.contains(biomeId);
	}

	public boolean hasCreator() {
		return creatorName != null && !creatorName.isBlank() && creatorId != null && !creatorId.isBlank();
	}
}
