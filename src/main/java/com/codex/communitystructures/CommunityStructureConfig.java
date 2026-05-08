package com.codex.communitystructures;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CommunityStructureConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "community_structures.json";

	public boolean enabled = true;
	public boolean useBuiltInStructureGeneration = true;
	public boolean legacyChunkEventPlacement = false;
	public String apiBaseUrl = "http://49.12.246.16:5174";
	public double landChancePerChunk = 1.0D;
	public double waterChancePerChunk = 0.0D;
	public double caveChancePerChunk = 0.0D;
	public int landSpacingChunks = 1;
	public int waterSpacingChunks = 12;
	public int caveSpacingChunks = 10;
	public int landSeparationChunks = 0;
	public int waterSeparationChunks = 5;
	public int caveSeparationChunks = 4;
	public int spawnProtectionRadiusChunks = 0;
	public int cachePerCategory = 10;
	public int downloadIntervalSeconds = 15;
	public int maxDownloadBytes = 128 * 1024 * 1024;
	public int minCaveY = -32;
	public int maxCaveY = 32;
	public int maxStructureWidth = 512;
	public int maxStructureHeight = 256;
	public int landMaxSlope = 24;
	public int landFoundationDepth = 8;
	public boolean landTerrainBlend = true;
	public int landMaxSinkDepth = 4;
	public int landTerrainBlendRadius = 8;
	public boolean landClearVegetation = true;
	public int landVegetationClearanceMargin = 2;
	public boolean landApplyBiomeSnow = true;
	public int minPlacementDistanceChunks = 0;
	public int maxWorldgenStartsPerSecond = 4;
	public int maxPlacementsPerTick = 1;
	public int maxStructureBlocksPerTick = 16384;
	public int progressivePlacementThresholdBlocks = 4096;
	public int maxPendingPlacementAttempts = 120;
	public boolean refillCacheAfterUse = true;
	public boolean includeEntities = false;

	public static CommunityStructureConfig load() {
		Path path = path();
		if (Files.exists(path)) {
			try (Reader reader = Files.newBufferedReader(path)) {
				CommunityStructureConfig config = GSON.fromJson(reader, CommunityStructureConfig.class);
				return config == null ? new CommunityStructureConfig() : config.normalized();
			} catch (IOException exception) {
				CommunityStructures.LOGGER.warn("Could not read {}, using defaults", path, exception);
			}
		}

		CommunityStructureConfig config = new CommunityStructureConfig();
		config.save(path);
		return config;
	}

	public CommunityStructureConfig normalized() {
		if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
			apiBaseUrl = "http://49.12.246.16:5174";
		}
		landChancePerChunk = clampChance(landChancePerChunk);
		waterChancePerChunk = 0.0D;
		caveChancePerChunk = 0.0D;
		landSpacingChunks = clampSpacing(landSpacingChunks);
		waterSpacingChunks = clampSpacing(waterSpacingChunks);
		caveSpacingChunks = clampSpacing(caveSpacingChunks);
		landSeparationChunks = clampSeparation(landSeparationChunks, landSpacingChunks);
		waterSeparationChunks = clampSeparation(waterSeparationChunks, waterSpacingChunks);
		caveSeparationChunks = clampSeparation(caveSeparationChunks, caveSpacingChunks);
		spawnProtectionRadiusChunks = Math.max(0, spawnProtectionRadiusChunks);
		cachePerCategory = Math.max(1, cachePerCategory);
		downloadIntervalSeconds = Math.max(15, downloadIntervalSeconds);
		maxDownloadBytes = Math.max(1024, maxDownloadBytes);
		maxStructureWidth = Math.max(1, maxStructureWidth);
		maxStructureHeight = Math.max(1, maxStructureHeight);
		landMaxSlope = Math.max(0, landMaxSlope);
		landFoundationDepth = Math.max(0, landFoundationDepth);
		landMaxSinkDepth = Math.max(0, Math.min(12, landMaxSinkDepth));
		landTerrainBlendRadius = Math.max(0, Math.min(24, landTerrainBlendRadius));
		landVegetationClearanceMargin = Math.max(0, Math.min(8, landVegetationClearanceMargin));
		minPlacementDistanceChunks = Math.max(0, minPlacementDistanceChunks);
		maxWorldgenStartsPerSecond = Math.max(1, Math.min(64, maxWorldgenStartsPerSecond));
		maxPlacementsPerTick = Math.max(1, maxPlacementsPerTick);
		maxStructureBlocksPerTick = Math.max(256, maxStructureBlocksPerTick);
		progressivePlacementThresholdBlocks = Math.max(0, progressivePlacementThresholdBlocks);
		maxPendingPlacementAttempts = Math.max(20, maxPendingPlacementAttempts);
		if (minCaveY > maxCaveY) {
			int oldMin = minCaveY;
			minCaveY = maxCaveY;
			maxCaveY = oldMin;
		}
		return this;
	}

	private static double clampChance(double chance) {
		if (Double.isNaN(chance)) {
			return 0.0D;
		}
		return Math.max(0.0D, Math.min(1.0D, chance));
	}

	private static int clampSpacing(int spacing) {
		return Math.max(1, Math.min(256, spacing));
	}

	private static int clampSeparation(int separation, int spacing) {
		return Math.max(0, Math.min(spacing - 1, separation));
	}

	public void save() {
		save(path());
	}

	private void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(normalized(), writer);
			}
		} catch (IOException exception) {
			CommunityStructures.LOGGER.warn("Could not write default config {}", path, exception);
		}
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}
}
