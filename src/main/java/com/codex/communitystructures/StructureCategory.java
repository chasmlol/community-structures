package com.codex.communitystructures;

import java.util.Optional;

public enum StructureCategory {
	LAND("land"),
	WATER("water"),
	CAVE("cave");

	private final String apiName;

	StructureCategory(String apiName) {
		this.apiName = apiName;
	}

	public String apiName() {
		return apiName;
	}

	public static Optional<StructureCategory> fromApiName(String apiName) {
		for (StructureCategory category : values()) {
			if (category.apiName.equals(apiName)) {
				return Optional.of(category);
			}
		}
		return Optional.empty();
	}
}
