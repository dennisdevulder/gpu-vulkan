# GPU Vulkan plugin compatibility matrix

Research date: 2026-05-22.

Scope:
- Built-in RuneLite plugins were taken from the local RuneLite client dependency used by this repo: `net.runelite:client:1.12.26.3`.
- Plugin Hub popularity was taken from `https://runelite.net/plugin-hub/`, sorted by active installs on 2026-05-22.
- Compatibility is for this repo's Vulkan renderer, not stock RuneLite GPU.

Legend:
- OK: expected to work.
- OK*: expected to work, but depends on the recent compatibility path in this repo.
- WARN: likely usable, but there is a known caveat or a manual test is needed.
- NO: not compatible with the current Vulkan renderer.

## Compatibility rules of thumb

| Plugin behavior | Vulkan status | Notes |
|---|---:|---|
| Normal RuneLite overlays, panels, infoboxes, chat/menu/UI changes | OK | These render through RuneLite's CPU UI buffer, which the Vulkan renderer uploads as the UI texture. |
| World/tile/NPC/player/object highlighting overlays | OK | These are usually CPU overlays projected through the client API, then composited through the UI buffer. |
| Screenshot consumers using `DrawManager` | OK* | Fixed by forwarding `DrawManager.processDrawComplete` and providing Vulkan readback instead of AWT screen capture. |
| Plugins that require the stock `GPU` plugin specifically | WARN/NO | If they check only for stock GPU, they may disable features even though Vulkan is rendering. |
| Plugins that own `DrawCallbacks` or provide their own GPU renderer | NO | RuneLite only has one draw-callback owner. This plugin refuses to coexist with stock GPU for this reason. |
| Plugins that require OpenGL FBO/texture access or stock GPU internals | NO | Needs an explicit Vulkan extension/backend integration. |
| Plugins that hide entities by relying on engine draw traversal | WARN | Manual actor capture can bypass some engine-side visibility paths; Entity Hider needs targeted testing. |

## Built-in RuneLite plugins

### NO

| Plugin(s) | Reason |
|---|---|
| GPU | Both renderers need to own `DrawCallbacks`; this plugin already refuses to start if stock GPU is enabled. |

### WARN

| Plugin(s) | Reason / test focus |
|---|---|
| Entity Hider | Test with manual actor capture on and off. Actor-list capture may render actors the engine path would otherwise hide. |
| FPS, Stretched Mode | Both interact with frame pacing/canvas scaling. Expected to work, but test resize, stretched fixed mode, and unlocked FPS combinations. |
| Low Memory | May change texture/scene behavior. Expected to work, but test texture loading after toggles and relog. |
| Roof Removal, Skybox | Vulkan has explicit skybox and roof-handling paths. Expected to work, but test region changes, instanced areas, and roof transitions. |
| Screenshot | Expected to work after the Vulkan readback fix. Test normal screenshots, auto screenshots, clipboard copy, and include-frame mode. |
| Dev Tools | General tools are OK; any GPU/OpenGL-specific inspection is not expected to apply to Vulkan internals. |
| Login Screen | Vulkan startup is deferred until logged in, so login-screen-specific visuals are outside this renderer's active path. |

### OK

| Plugin(s) | Notes |
|---|---|
| Account, Diary Requirements, Agility, Ammo, Animation Smoothing, Anti Drag, Attack Styles | Normal client state/UI/overlay plugins. |
| Bank, Bank Tags, Grand Exchange, Item Charges, Item Identification, Item Prices, Item Stats | Bank/item/UI overlays and panels should composite normally. |
| Barbarian Assault, Barrows, Blast Furnace, Blast Mine, Boss Timers, Cannon, Cooking, Corp, Drift Net, Fairy Ring, Fishing, Herbiboar, Hunter, Implings, Kourend Library, Mining, Motherlode, MTA, Nightmare Zone, Pest Control, Pyramid Plunder, Raids, Runecraft, Slayer, Smelting, Tears of Guthix, Tithe Farm, Wintertodt, Woodcutting, Zalcano | Activity helpers mostly use overlays, infoboxes, widgets, and client state. |
| Boosts, Combat Level, DPS Counter, Opponent Info, Poison, Prayer, Regen Meter, Run Energy, Special Counter, Status Bars, Timers and Buffs, Virtual Levels, XP Drop, XP Globes, XP Tracker, XP Updater | HUD/infobox/stat plugins should be rendered through the UI texture. |
| Camera, Instance Map, Minimap, World Map, World Hopper | Client-state/UI behavior; no renderer ownership expected. |
| Chat Channel, Chat Commands, Chat Filter, Chat History, Chat Notifications, Emojis, Timestamp | Chat/UI behavior only. |
| Clue Scroll, Daily Tasks, Puzzle Solver, Quest List, Random Events, Report Button, Runepouch, Skill Calculator, Spellbook, Time Tracking, Wiki | UI, overlays, and client data. |
| Config, Searchable Plugin, Info, Notes | Internal/settings/panel behavior. |
| Crowdsourcing, Discord, Examine, Friend List, Friend Notes, Hiscore, Loot Tracker, Party, Twitch | Data/network/panel integrations; no render ownership expected. |
| Custom Cursor, Default World, Idle Notifier, Interface Styles, Inventory Grid, Inventory Tags, Inventory Viewer, Key Remapping, Kingdom of Miscellania, Logout Timer, Menu Entry Swapper, Metronome, Music, Poh, Team | UI/client behavior expected to work. |
| Ground Items, Ground Marker, Interact Highlight, Mouse Highlight, NPC Indicators, NPC Aggro Area, Object Indicators, Player Indicators, Screen Markers, Tile Indicators | Overlay/highlight plugins should composite normally through RuneLite's UI buffer. |

## Top 25 Plugin Hub plugins by active installs

| Rank | Plugin | Active installs | Status | Notes |
|---:|---|---:|---:|---|
| 1 | Quest Helper | 978,491 | OK | Widget, panel, world overlay, and tile-marker style behavior. |
| 2 | 117 HD | 674,153 | NO | It is its own GPU renderer with HD materials/lighting/post-processing. Needs a first-class Vulkan extension port. |
| 3 | WikiSync | 610,847 | OK | Data sync; renderer-independent. |
| 4 | Guardians of the Rift Helper | 605,332 | OK | Minigame overlays/widgets. |
| 5 | Tile Packs | 594,761 | OK | Tile markers/overlays should composite normally. |
| 6 | Sailing | 578,998 | OK | Utility overlays/widgets; test new Sailing-specific world overlays. |
| 7 | Tombs of Amascut | 554,130 | OK | Raid overlays/timers; no renderer ownership expected. |
| 8 | Better NPC Highlight | 505,154 | OK | Overlay/highlight plugin; should render through UI buffer. |
| 9 | The Gauntlet | 494,797 | OK | Map/overlay/timer helpers. |
| 10 | Zulrah Plugin | 484,578 | OK | Panel/overlay rotations. |
| 11 | Mahogany Homes | 445,297 | OK | Contract helper overlays/widgets. |
| 12 | Tempoross | 431,534 | OK | Timers/highlights/objects. |
| 13 | Hunter Rumours | 430,818 | OK | Tracking/panel/overlays. |
| 14 | Banked Experience | 423,855 | OK | Bank interface calculations. |
| 15 | Rogues' Den | 422,735 | OK | Tile/clickbox overlays. |
| 16 | Easy Giants' Foundry | 421,723 | OK | Minigame overlays/UI. |
| 17 | Mastering Mixology | 411,365 | OK | Interface augmentation. |
| 18 | Bank Tag Layouts | 401,671 | OK | Bank tag UI manipulation. |
| 19 | Fight Cave Waves | 396,470 | OK | Wave panel/overlays. |
| 20 | Hub Party Panel | 390,672 | OK | Side panel/data display. |
| 21 | Port Tasks | 389,607 | OK | World/map overlays and tracking. |
| 22 | Skills Progress | 388,513 | OK | Skills tab UI overlays. |
| 23 | Inventory Setups | 369,705 | OK | Panels/bank/inventory UI. |
| 24 | Totem Fletching | 368,968 | OK | Activity helper overlays/widgets. |
| 25 | Fight Caves Spawn Predictor | 347,023 | OK | Spawn prediction panel/overlay. |

## Follow-up tests worth running

| Test | Why |
|---|---|
| Screenshot plugin: normal, auto, clipboard, include-frame | Confirms the Vulkan readback path covers all `DrawManager` consumers. |
| Entity Hider with manual actor capture on/off | Most likely built-in behavior mismatch. |
| 117 HD enabled alongside GPU Vulkan | Should remain blocked or clearly fail; accidental double renderer ownership is unsafe. |
| Stretched Mode + resize storms on macOS and Linux | Exercises viewport/canvas scaling and swapchain/drawable resize paths. |
| Roof Removal + Skybox across region transitions | Verifies Vulkan-specific roof/skybox implementation against common built-ins. |
| Better NPC Highlight, Tile Packs, Quest Helper, Rogues' Den | High-volume overlay smoke test for top Plugin Hub plugins. |

## Sources

- RuneLite Plugin Hub: https://runelite.net/plugin-hub/
- RuneLite Plugin Hub wiki: https://github.com/runelite/runelite/wiki/Information-about-the-Plugin-Hub
- Local dependency inspected for built-in plugin class list: `~/.gradle/caches/modules-2/files-2.1/net.runelite/client/1.12.26.3/.../client-1.12.26.3.jar`
