package com.codex.communitystructures;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CommunityStructures implements ModInitializer {
	public static final String MOD_ID = "community_structures";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static CommunityStructureConfig config;
	private static CommunityStructureCache cache;

	@Override
	public void onInitialize() {
		CommunityStructureCapturePackets.register();
		CommunityStructureWorldgen.register();

		config = CommunityStructureConfig.load();
		cache = new CommunityStructureCache(config);
		CommunityStructureCapture.register();
		CommunityStructureChat.register();

		ServerLifecycleEvents.SERVER_STARTING.register(server -> cache.start());
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			cache.stop();
			CommunityStructurePlacer.shutdown();
		});
		if (config.legacyChunkEventPlacement) {
			ServerChunkEvents.CHUNK_GENERATE.register(CommunityStructurePlacer::tryPlace);
			ServerTickEvents.END_WORLD_TICK.register(CommunityStructurePlacer::tick);
		}

		LOGGER.info("Community Structures loaded with API {}", config.apiBaseUrl);
	}

	public static CommunityStructureConfig config() {
		return config;
	}

	public static CommunityStructureCache cache() {
		return cache;
	}
}
