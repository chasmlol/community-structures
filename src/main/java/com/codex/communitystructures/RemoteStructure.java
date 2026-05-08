package com.codex.communitystructures;

import java.util.List;

public record RemoteStructure(String id, String name, String category, String placementPreset, String downloadUrl, List<String> allowedBiomes, String creatorName, String creatorId) {
}
