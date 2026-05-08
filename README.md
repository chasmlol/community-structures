# Community Structures

Fabric 1.21.1 prototype for cached, user-uploaded structure generation.

The mod talks to the Community Structures API/site backend configured by `apiBaseUrl`.
It downloads cached `land`, `water`, and `cave` `.nbt` files into:

```text
config/community_structures/cache
```

Generation now runs through Minecraft's built-in structure pipeline with a custom dynamic `Structure` and `StructurePiece`. The JSON structure sets ask every chunk, then the Java structure applies the Mod Menu spacing/frequency settings and pulls one cached upload into a durable generated copy for chunk-by-chunk placement.

## Build

```powershell
.\gradlew.bat build
```

Copy `build/libs/community-structures-0.1.2.jar` into the Minecraft profile's `mods` folder.

## Config

The first run writes:

```text
config/community_structures.json
```

Important defaults:

- `apiBaseUrl`: `http://49.12.246.16:5174`
- `useBuiltInStructureGeneration`: `true`
- `legacyChunkEventPlacement`: `false`
- `landChancePerChunk`, `waterChancePerChunk`, `caveChancePerChunk`: candidate frequency sliders
- `landSpacingChunks`, `waterSpacingChunks`, `caveSpacingChunks`: vanilla-style spread grid spacing
- `maxWorldgenStartsPerSecond`: hard burst cap for structure starts while flying quickly
- `cachePerCategory`: `5` newest never-generated structures to keep ready locally
- `downloadIntervalSeconds`: `15`

Structure chat uses a WebSocket connection to `/api/chat/live` for instant delivery, with the older HTTP polling endpoint kept as a fallback.

The client checks GitHub releases on startup. If a newer release exists, it posts a clickable update message in chat. Clicking it downloads the newest jar and schedules the replacement for after Minecraft closes; the player then starts Minecraft again manually.

In-game capture uses `K` for tracked player-placed blocks and `L` for every non-air block inside the 20x20x20 capture box. `J` cancels the current preview. Container contents are never copied from uploads; all uploaded containers stay in the structure, and up to two are filled with random village loot when the structure generates.

Use `/bless` while standing within 10 blocks of a generated community structure to open a small chest UI. Items placed in that chest are sent through the API to the structure creator and delivered when they are online.

Structure cache requests include the local player's UUID and name in request headers so the API can apply per-account download limits, with IP limits as a fallback for unknown clients.

Downloaded structures stay on disk after generation as the local used cache. The active ready queue is filled from the newest online structures the player has not generated yet; only when the online database has zero unseen structures does the mod reuse a random eligible structure from the local used cache.

When a player dies without keep-inventory enabled, the mod can send a death recovery event through the API. Another live modded player gets a named zombie wearing the dead player's head; killing it before the timer expires returns the dead player's saved inventory, offhand, armor, selected hotbar slot, and XP.
