# Community Structures generation notes

Last updated: 2026-05-07

## Sources checked

- Fabric API `ServerChunkEvents.Generate`: https://maven.fabricmc.net/docs/fabric-api-0.119.2%2B1.21.4/net/fabricmc/fabric/api/event/lifecycle/v1/ServerChunkEvents.Generate.html
- Yarn `StructurePlacementType`, including vanilla `RANDOM_SPREAD`: https://maven.fabricmc.net/docs/yarn-1.21.6%2Bbuild.1/net/minecraft/world/gen/chunk/placement/StructurePlacementType.html
- YUNG's API `EnhancedRandomSpread`: https://github.com/YUNG-GANG/YUNGs-API/blob/1.21.1/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/world/structure/placement/EnhancedRandomSpread.java
- YUNG's API `YungJigsawStructure`: https://github.com/YUNG-GANG/YUNGs-API/blob/1.21.1/Common/src/main/java/com/yungnickyoung/minecraft/yungsapi/world/structure/YungJigsawStructure.java
- Mo' Structures `ModStructure`: https://github.com/frqnny/mostructures/blob/1.21.1/common/src/main/java/io/github/frqnny/mostructures/structure/ModStructure.java
- Sparse Structures placement rewrite: https://github.com/MaxenceDC/sparsestructures/blob/main/fabric/src/main/java/io/github/maxencedc/sparsestructures/mixin/MakeStructuresSparse.java

Local reference checkouts:

- `C:\minecraft\reference-mods\YUNGs-API`
- `C:\minecraft\reference-mods\YUNGs-Better-Dungeons`
- `C:\minecraft\reference-mods\YUNGs-Better-Ocean-Monuments`
- `C:\minecraft\reference-mods\mostructures`
- `C:\minecraft\reference-mods\SparseStructures`

## What structure mods usually do

Vanilla and the mature structure mods do not roll "maybe spawn here" for every generated chunk. They use deterministic placement maps.

The common pattern is:

1. Divide chunk coordinates into regions using a spacing value.
2. Use the world seed, region coordinates, and a structure-specific salt to pick one candidate chunk inside that region.
3. Optionally apply a frequency roll to that one candidate.
4. Run biome, heightmap, liquid, slope, and other placement checks.
5. Build through structure pieces or jigsaw/template generation.

YUNG's `EnhancedRandomSpread` keeps vanilla `RandomSpreadStructurePlacement` and adds extra gates such as exclusion zones. Mo' Structures uses a custom `Structure` subclass that calls `StructurePoolBasedGenerator` and can reject steep terrain before generation. Sparse Structures mainly changes vanilla structure-set spacing/separation while preserving the same placement model.

## Why our previous approach caused weirdness

Our first generator rolled every newly generated chunk. At high test settings that meant many land, water, and cave attempts could enter the queue at once. The cache, terrain checks, full-footprint-loaded checks, and large NBT placement then had to fight a burst of work. That creates exactly the symptoms we saw: dead stretches, sudden lag buffers, slow chunk loading, and sometimes a structure appearing long after the chunk that triggered it.

## Current rewrite decision

The mod now uses the long-term target: a registered vanilla-style structure pipeline.

- `community_structures:dynamic_structure` is a real `Structure` type.
- `community_structures:dynamic_piece` is a real `StructurePiece` type.
- Datapack JSON registers five placement presets as structures/structure sets:
  - `land_surface_house` (`surface_structure` preset)
  - `land_surface_ruin`
  - `land_buried_ruin`
  - `water_ocean_floor`
  - `cave_room`
- The JSON structure sets use spacing `1` only so Minecraft asks about every chunk. The Java structure then applies the live Mod Menu spacing, separation, and frequency sliders. That preserves runtime tuning without a custom placement codec.
- When a candidate is accepted, the cache reserves one downloaded `.nbt`, moves it to a durable generated file, removes it from the active queue, and triggers the downloader to refill. This preserves the "use one, fetch another" online behavior while keeping a stable file path for chunk-by-chunk structure-piece generation and world saves.
- Land/water/cave are separate spread streams with separate salts. Presets also get separate salts where needed.
- The cache now moves the selected `.nbt` into the generated folder instead of copying it, avoiding large synchronous file copies during chunk generation.
- `maxWorldgenStartsPerSecond` caps bursty starts while flying quickly. This matters at test settings where all three category frequencies are set to 100%.
- The piece placer scans only the current chunk's bounding box instead of building a full all-chunks block index up front. This spreads big structures across chunk generation work and avoids a single giant bucket-building pause.

## Terrain behavior

Built-in `terrain_adaptation` is now the main blending layer:

- Surface structures and ruins use `beard_thin`, like villages and other surface jigsaw structures.
- Buried ruins use `bury`, like trail-ruin style generation.
- Ocean floor structures use `beard_thin` and sample `OCEAN_FLOOR_WG`.
- Cave rooms use `beard_box`.

The piece placer also lets generated terrain win over land-structure partial blocks. If a slab, crop, flower, campfire, or other non-full block would be embedded into already-solid generated terrain, the mod skips that uploaded block instead of carving a weird hole or forcing the partial block through the terrain.
