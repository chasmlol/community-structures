package com.codex.communitystructures.client;

import com.codex.communitystructures.CommunityStructureConfig;
import com.codex.communitystructures.CommunityStructures;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

public final class CommunityStructuresModMenu implements ModMenuApi {
	private static final CommunityStructureConfig DEFAULTS = new CommunityStructureConfig().normalized();

	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CommunityStructuresModMenu::createConfigScreen;
	}

	private static Screen createConfigScreen(Screen parent) {
		CommunityStructureConfig config = CommunityStructures.config();
		ConfigBuilder builder = ConfigBuilder.create()
			.setParentScreen(parent)
			.setTitle(Text.literal("Community Structures"))
			.setSavingRunnable(config::save);
		ConfigEntryBuilder entries = builder.entryBuilder();

		ConfigCategory generation = builder.getOrCreateCategory(Text.literal("Generation"));
		generation.addEntry(entries.startBooleanToggle(Text.literal("Enabled"), config.enabled)
			.setDefaultValue(DEFAULTS.enabled)
			.setTooltip(Text.literal("Master switch for community structure generation."))
			.setSaveConsumer(value -> config.enabled = value)
			.build());
		generation.addEntry(entries.startBooleanToggle(Text.literal("Built-in structure pipeline"), config.useBuiltInStructureGeneration)
			.setDefaultValue(DEFAULTS.useBuiltInStructureGeneration)
			.setTooltip(Text.literal("Uses Minecraft's registered structure generation, terrain adaptation, and chunk-safe pieces. Requires a world reload after changing."))
			.setSaveConsumer(value -> config.useBuiltInStructureGeneration = value)
			.build());
		generation.addEntry(entries.startBooleanToggle(Text.literal("Legacy chunk placer"), config.legacyChunkEventPlacement)
			.setDefaultValue(DEFAULTS.legacyChunkEventPlacement)
			.setTooltip(Text.literal("Old post-chunk placement path for debugging only. Leave off for normal worlds. Requires a game restart after changing."))
			.setSaveConsumer(value -> config.legacyChunkEventPlacement = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Land frequency"), chanceToSlider(config.landChancePerChunk), 0, 10000)
			.setDefaultValue(chanceToSlider(DEFAULTS.landChancePerChunk))
			.setTextGetter(CommunityStructuresModMenu::chanceText)
			.setTooltip(Text.literal("Chance for each land spread-grid candidate to try. Max no longer means every chunk."))
			.setSaveConsumer(value -> config.landChancePerChunk = sliderToChance(value))
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Land grid spacing"), config.landSpacingChunks, 1, 64)
			.setDefaultValue(DEFAULTS.landSpacingChunks)
			.setTextGetter(CommunityStructuresModMenu::chunksText)
			.setTooltip(Text.literal("Vanilla-style spacing grid for land candidates. 1 chunk is the densest testing mode."))
			.setSaveConsumer(value -> config.landSpacingChunks = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Land separation"), config.landSeparationChunks, 0, 63)
			.setDefaultValue(DEFAULTS.landSeparationChunks)
			.setTextGetter(CommunityStructuresModMenu::chunksText)
			.setTooltip(Text.literal("Minimum empty area inside each land spacing grid. Must be lower than spacing."))
			.setSaveConsumer(value -> config.landSeparationChunks = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Spawn protection"), config.spawnProtectionRadiusChunks, 0, 64)
			.setDefaultValue(DEFAULTS.spawnProtectionRadiusChunks)
			.setTextGetter(value -> Text.literal(value + " chunks"))
			.setTooltip(Text.literal("Prevents structures from generating near world spawn."))
			.setSaveConsumer(value -> config.spawnProtectionRadiusChunks = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Minimum spacing"), config.minPlacementDistanceChunks, 0, 32)
			.setDefaultValue(DEFAULTS.minPlacementDistanceChunks)
			.setTextGetter(value -> Text.literal(value + " chunks"))
			.setTooltip(Text.literal("Prevents structures from being placed too close to recently generated structures."))
			.setSaveConsumer(value -> config.minPlacementDistanceChunks = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Start burst limit"), config.maxWorldgenStartsPerSecond, 1, 32)
			.setDefaultValue(DEFAULTS.maxWorldgenStartsPerSecond)
			.setTextGetter(value -> Text.literal(value + " per second"))
			.setTooltip(Text.literal("Hard cap for new built-in structure starts while flying quickly. Lower values smooth chunk generation."))
			.setSaveConsumer(value -> config.maxWorldgenStartsPerSecond = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Placements per tick"), config.maxPlacementsPerTick, 1, 8)
			.setDefaultValue(DEFAULTS.maxPlacementsPerTick)
			.setTextGetter(value -> Text.literal(value + " per tick"))
			.setTooltip(Text.literal("Lower values reduce chunk-generation stutter."))
			.setSaveConsumer(value -> config.maxPlacementsPerTick = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Structure blocks per tick"), config.maxStructureBlocksPerTick, 256, 32768)
			.setDefaultValue(DEFAULTS.maxStructureBlocksPerTick)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Large structures are placed gradually with this per-tick block budget. Lower means smoother but slower."))
			.setSaveConsumer(value -> config.maxStructureBlocksPerTick = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Gradual placement threshold"), config.progressivePlacementThresholdBlocks, 0, 100000)
			.setDefaultValue(DEFAULTS.progressivePlacementThresholdBlocks)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Structures with at least this many blocks are placed over multiple ticks. Set 0 to make every structure gradual."))
			.setSaveConsumer(value -> config.progressivePlacementThresholdBlocks = value)
			.build());
		generation.addEntry(entries.startIntSlider(Text.literal("Pending wait"), config.maxPendingPlacementAttempts, 20, 10000)
			.setDefaultValue(DEFAULTS.maxPendingPlacementAttempts)
			.setTextGetter(value -> Text.literal(value + " attempts"))
			.setTooltip(Text.literal("How long a large structure can wait for its full footprint to be loaded."))
			.setSaveConsumer(value -> config.maxPendingPlacementAttempts = value)
			.build());

		ConfigCategory cache = builder.getOrCreateCategory(Text.literal("Online Cache"));
		cache.addEntry(entries.startTextField(Text.literal("API URL"), config.apiBaseUrl)
			.setDefaultValue(DEFAULTS.apiBaseUrl)
			.setTooltip(Text.literal("The website/server the mod downloads structures from."))
			.setSaveConsumer(value -> config.apiBaseUrl = value)
			.build());
		cache.addEntry(entries.startIntSlider(Text.literal("New cache target"), config.cachePerCategory, 1, 50)
			.setDefaultValue(DEFAULTS.cachePerCategory)
			.setTextGetter(value -> Text.literal(value + " structures"))
			.setTooltip(Text.literal("How many never-generated land structures to keep ready locally. Already generated structures stay cached for fallback reuse."))
			.setSaveConsumer(value -> config.cachePerCategory = value)
			.build());
		cache.addEntry(entries.startIntSlider(Text.literal("Download interval"), config.downloadIntervalSeconds, 15, 600)
			.setDefaultValue(DEFAULTS.downloadIntervalSeconds)
			.setTextGetter(value -> Text.literal(value + " seconds"))
			.setTooltip(Text.literal("How often the background downloader checks the queue."))
			.setSaveConsumer(value -> config.downloadIntervalSeconds = value)
			.build());
		cache.addEntry(entries.startBooleanToggle(Text.literal("Refill cache after use"), config.refillCacheAfterUse)
			.setDefaultValue(DEFAULTS.refillCacheAfterUse)
			.setTooltip(Text.literal("Turn off when testing one specific cached upload."))
			.setSaveConsumer(value -> config.refillCacheAfterUse = value)
			.build());
		cache.addEntry(entries.startIntSlider(Text.literal("NBT read budget"), bytesToMegabytes(config.maxDownloadBytes), 1, 256)
			.setDefaultValue(bytesToMegabytes(DEFAULTS.maxDownloadBytes))
			.setTextGetter(value -> Text.literal(value + " MB"))
			.setTooltip(Text.literal("Maximum uncompressed NBT read budget for one cached structure. Large litematics may need this raised."))
			.setSaveConsumer(value -> config.maxDownloadBytes = value * 1024 * 1024)
			.build());

		ConfigCategory placement = builder.getOrCreateCategory(Text.literal("Placement"));
		placement.addEntry(entries.startIntSlider(Text.literal("Max width/depth"), config.maxStructureWidth, 8, 512)
			.setDefaultValue(DEFAULTS.maxStructureWidth)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Structures wider than this are skipped."))
			.setSaveConsumer(value -> config.maxStructureWidth = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Max height"), config.maxStructureHeight, 8, 256)
			.setDefaultValue(DEFAULTS.maxStructureHeight)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Structures taller than this are skipped."))
			.setSaveConsumer(value -> config.maxStructureHeight = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Land max slope"), config.landMaxSlope, 0, 128)
			.setDefaultValue(DEFAULTS.landMaxSlope)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Land structures are skipped if the sampled footprint is steeper than this."))
			.setSaveConsumer(value -> config.landMaxSlope = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Land foundation depth"), config.landFoundationDepth, 0, 128)
			.setDefaultValue(DEFAULTS.landFoundationDepth)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("How far land structures may fill small gaps underneath with local terrain blocks."))
			.setSaveConsumer(value -> config.landFoundationDepth = value)
			.build());
		placement.addEntry(entries.startBooleanToggle(Text.literal("Land terrain blending"), config.landTerrainBlend)
			.setDefaultValue(DEFAULTS.landTerrainBlend)
			.setTooltip(Text.literal("Sinks authored terrain bases and builds a local-material skirt around land structures."))
			.setSaveConsumer(value -> config.landTerrainBlend = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Land max sink"), config.landMaxSinkDepth, 0, 12)
			.setDefaultValue(DEFAULTS.landMaxSinkDepth)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Maximum auto-sink depth for structures with detected terrain bases."))
			.setSaveConsumer(value -> config.landMaxSinkDepth = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Land blend radius"), config.landTerrainBlendRadius, 0, 24)
			.setDefaultValue(DEFAULTS.landTerrainBlendRadius)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("How far the terrain skirt can feather outward from authored land bases."))
			.setSaveConsumer(value -> config.landTerrainBlendRadius = value)
			.build());
		placement.addEntry(entries.startBooleanToggle(Text.literal("Clear land vegetation"), config.landClearVegetation)
			.setDefaultValue(DEFAULTS.landClearVegetation)
			.setTooltip(Text.literal("Removes existing trees, leaves, vines, and plants from land structure air space."))
			.setSaveConsumer(value -> config.landClearVegetation = value)
			.build());
		placement.addEntry(entries.startIntSlider(Text.literal("Vegetation clear margin"), config.landVegetationClearanceMargin, 0, 8)
			.setDefaultValue(DEFAULTS.landVegetationClearanceMargin)
			.setTextGetter(value -> Text.literal(value + " blocks"))
			.setTooltip(Text.literal("Extra margin around land structures for clearing tree leaves and trunks."))
			.setSaveConsumer(value -> config.landVegetationClearanceMargin = value)
			.build());
		placement.addEntry(entries.startBooleanToggle(Text.literal("Apply biome snow"), config.landApplyBiomeSnow)
			.setDefaultValue(DEFAULTS.landApplyBiomeSnow)
			.setTooltip(Text.literal("Adds snow layers to exposed land structure tops when Minecraft says snow can form there."))
			.setSaveConsumer(value -> config.landApplyBiomeSnow = value)
			.build());
		placement.addEntry(entries.startBooleanToggle(Text.literal("Include entities"), config.includeEntities)
			.setDefaultValue(DEFAULTS.includeEntities)
			.setTooltip(Text.literal("Allows entities from uploaded structures. Keep off for safer community uploads."))
			.setSaveConsumer(value -> config.includeEntities = value)
			.build());

		return builder.build();
	}

	private static int chanceToSlider(double chance) {
		return (int) Math.round(Math.max(0.0D, Math.min(1.0D, chance)) * 10000.0D);
	}

	private static double sliderToChance(int value) {
		return Math.max(0, Math.min(10000, value)) / 10000.0D;
	}

	private static Text chanceText(int value) {
		double percent = value / 100.0D;
		return Text.literal(String.format("%.2f", percent) + "% of candidates");
	}

	private static Text chunksText(int value) {
		return Text.literal(value + " chunks");
	}

	private static int bytesToMegabytes(int bytes) {
		return Math.max(1, bytes / 1024 / 1024);
	}
}
