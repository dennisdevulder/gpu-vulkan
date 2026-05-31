# GPU (Vulkan) — Known Issues & Diagnostic Notes

State as of this session. Plugin works for normal gameplay (rendering, scene
capture, lighting, fog, animated textures, etc.). The issues below all have
real diagnostic data attached so we don't have to re-discover them.

---

## 1. Sidebar collapse arrow crash on X11 (MITIGATED)

### Symptom
Plugin ran fine, but clicking the chevron/arrow that collapses the RuneLite
plugin sidebar could make the RuneLite window vanish.

Current status: mitigated in code. On non-macOS platforms the plugin now
creates and keeps an rlawt GL context attached to RuneLite's canvas while
Vulkan renders through the Vulkan surface. AWT sees a normal GL-owned canvas
during layout rebuilds, which avoids the native XAWT crash observed during
sidebar-collapse animation.

### Diagnostic data
From `strace -e trace=exit,exit_group,kill,tgkill --decode-pids=comm`:

```
<AWT-EventQueue-> SIGSEGV {si_code=SEGV_MAPERR, si_addr=0x8}    ← unrecoverable
<AWT-EventQueue-> exit_group(1)                                   ← JVM gives up
```

The fault is on the **AWT EventQueue thread** (not Client thread, not our
Vulkan code). NULL+8-byte-offset deref in native code = dereferencing a struct
field at offset 8 from a NULL `this`-pointer in C/C++. The pattern matches
AWT's libawt_xawt.so walking its canvas-peer struct during the layout
animation triggered by the sidebar collapse.

The JVM's signal handler tries to recover (it normally translates SIGSEGV at
known JIT-NPE sites into Java NPEs), fails because the fault is in unmanaged
native code, and calls `exit_group(1)` to abort. No hs_err written because the
recovery itself failed before reaching the hs_err writer.

### Why it doesn't hit stock GpuPlugin
Stock uses `awtContext.createGLContext()` which integrates with AWT's canvas
peer model via GLX. AWT knows how to coordinate canvas reshape with a GL-owned
surface. We use raw JAWT + `KHR_xlib_surface` directly to the X11 window —
AWT doesn't know we're there, so when its layout cascade walks the canvas
peer it hits a state Vulkan WSI silently changed under it.

### Things tried that DID NOT fix it
- `awtContext.createGLContext()` (with `detachCurrent()`) — AWT happier but
  same crash; also caused engine `paint=0` issue (engine stopped firing scene
  paints with a live GL context).
- Removing rlawt entirely — same crash.
- Switching `KHR_xlib_surface` → `KHR_xcb_surface` (with `XGetXCBConnection`
  bridge) — same crash AND added rendering glitches. Reverted.
- Eager swapchain rebuild on canvas dimension change — caused 12+ swapchain
  rebuilds during the resize storm, drove `paint=0` in stats, made things
  worse. Reverted; we're back to lazy rebuild on `SUBOPTIMAL_KHR`.
- Fence-wait at start of `drawScene` — broke teardown (race during disable),
  didn't fix glitches. Reverted.

### Fix used
Keep the rlawt GL context alive on X11 while Vulkan renders. We do not issue
GL draws through that context; it exists to keep AWT's canvas peer and resize
path in the state XAWT expects during layout changes.

### Remaining verification
Retest sidebar collapse on Linux/X11 after any future change to startup,
shutdown, surface creation, or swapchain resize behavior.

---

## 2. Validation layer SIGSEGVs on plugin disable

### Symptom
With validation enabled (`gpuvulkan.validation=true`), plugin disable crashes
the JVM with SIGSEGV/SIGABRT inside `libVkLayer_khronos_validation.so`.
Crash is deterministic, fixed at offset `0x409696`.

### Diagnostic data
From `coredumpctl gdb`:

```
#0 abort
#1 vvl::dispatch::GetData(VkDevice_T*) [clone .cold]
#2 vulkan_layer_chassis::WaitForFences(VkDevice, ...)
#3 [our drawFrame's vkWaitForFences call]
```

The validation layer's per-device dispatch lookup misses our `VkDevice` and
the layer aborts. From the disassembly at `0x409696`, the layer is iterating
its internal validation-objects list and dereferencing a stale entry.

### Root cause
Bug in the Fedora-packaged `vulkan-validation-layers-1.4.341.0-2.fc43` on top
of Mesa 25.3.6 RADV. The layer's own internal STL containers leak dangling
pointers across multiple `vkCreateInstance`/`vkDestroyInstance` cycles in the
same JVM lifetime. We can't reach into the layer's heap from app code.

### Fix path
- Default `gpuvulkan.validation=false` in profile (already done — user's
  profile flipped manually)
- File upstream bug at github.com/KhronosGroup/Vulkan-ValidationLayers
- Try newer layer build from LunarG SDK; point `VK_LAYER_PATH` at it via
  `vkdev` script

### Status
Worked around (validation off by default). Re-enable for short debug sessions
only; expect crash on next plugin disable.

---

## 3. Roof culling — tile-roof culling wired (verify visually)

### What it does now
At `captureScene`, for every `SceneTilePaint` / `SceneTileModel` whose
`Scene.getRoofs()[plane][tx][tz]` is non-zero, the (roofId, vertexStart,
vertexCount) triple is recorded into parallel arrays. At `recordDraw`, the
current frame's `hideRoofIds` is used to build a sorted skip-list of those
ranges; TERRAIN is drawn as `[start, end)` minus the skips via
`drawWithSkips`. Mirrors stock GpuPlugin's mechanism (which offloads to
`Zone.renderOpaque(..., hideRoofIds)`) but evaluates at our draw time
because we capture geometry up front rather than per-zone.

### Verify
At Wintertodt camp on plane 0, the tent's roof tiles should disappear like
in stock. If a roof element is still visible, it's either:
(a) Not a tile — it's a GameObject with no roof tag (engine doesn't tell us
    which GameObjects belong to a roof group via the public API).
(b) RoofRemovalPlugin disabled — `scene.getRoofs()` is then all-zero and
    nothing gets skipped (this is correct behavior).

---

## 3b. Wintertodt brick wall — RESOLVED (bridge-tile recursion)

**Fix landed.** captureScene now recurses into `Tile.getBridge()` for
every static layer, mirroring stock's `SceneUploader.java:366-370`.
History below is kept as the diagnostic playbook for the next "geometry
silently missing" bug.

### Symptom
At Wintertodt camp, the brick wall immediately to the left of the player
character is missing — see straight through to snow. Stock renders it
fine. The wall section is not occluded; it simply doesn't appear in our
framebuffer.

### What we know (from diagnostic instrumentation)
The plugin has a `SceneRenderer` diagnostic mode that:
1. Paints each static layer a distinct solid color via a sentinel
   `texLayer` value (1000=WALLS magenta, 1001=TERRAIN cyan, 1003=DECORATIVE
   yellow, 1004=GROUND orange, 1005=GAME_OBJECTS light blue,
   1006=any plane>0 lime green) — handled in `scene.frag` and
   `paintLayerSentinel(...)` in `SceneRenderer.java`.
2. Forces all planes to render by overriding `loMin=0, loMax=MAX_PLANES-1`
   in `recordDraw`.
3. Logs textured-vertex counts per layer and other capture stats at the
   end of `captureScene`.

Running this in-game at Wintertodt:
- Magenta (walls) appears on the upper camp railings/perimeter — wall
  capture and rendering DOES work for those `WallObject`s.
- The specific missing wall left of the character is **not** any color.
  It's not in `WallObject`, `Terrain` (paint or model), `DecorativeObject`,
  `GroundObject`, `GameObject`, OR plane>0 of any of those.

### What this rules out
- It's not a shader discard or alpha-pipeline issue (we removed those
  one-by-one and forced opaque output earlier in the same investigation
  — no change).
- It's not capture failing for WallObjects (we capture 197 WallObjects
  producing 152508 vertices, all on plane 0; the magenta path proves
  those reach the rasterizer).
- It's not the plane filter (we force-render all 4 planes in the
  diagnostic build and the wall still doesn't appear).
- It's not texture-data loading (TEXARR log shows 208/209 textures loaded
  with real pixels).
- It's not the `getFaceTextures` array being null/empty for this geometry
  (we counted: model-has-textures stats logged in captureScene).

### Remaining hypothesis (untested)
The missing geometry is in a `Tile.getBridge()` tile we don't process.
Stock recursively processes bridge tiles (`SceneUploader.java:366-370`);
our `captureScene` doesn't. Bridge tiles in OSRS exist for areas where
the player walks "under" something — the bridge tile holds the secondary
plane's paint/model/wall/decorative/gameobject data. If the Wintertodt
camp structure uses bridge tiles for some of its walls, our capture
silently skips them.

### Next-step recipe (when picked up again)
1. In `captureScene`, when iterating tiles, also recurse into
   `t.getBridge()` if non-null. Mirror stock's `uploadZoneTile` recursion
   shape — same paint/model/wall/decorative/ground/gameobject capture for
   the bridge tile, using the bridge tile's `getSceneLocation()` (or just
   `(sx, sy)` of the parent tile) as the tile coord.
2. Rebuild. Re-run diagnostic with the sentinels still in place. If a
   wall-shaped magenta patch now appears at the missing wall's location,
   bridge tiles were the answer.
3. If still nothing, instrument every Tile API entry one-by-one (already
   covered: paint, model, wall, decorative, ground, gameobjects; NOT yet
   covered: `getBridge`, `getItemLayer`, `getGroundItems`).

### Diagnostic build state
- `scene.frag` has the per-layer sentinel-color block at the top of
  `main()`. Remove the seven `if (vTexLayer == 100Xu) ...` lines to
  return to normal rendering.
- `SceneRenderer.recordDraw` forces `loMin=0, loMax=MAX_PLANES-1`. Revert
  to `loMin=minPlane, loMax=maxPlane` to restore real plane filtering.
- `SceneRenderer.captureScene` calls `paintLayerSentinel(...)` over every
  layer at the end. Remove that block to stop rewriting captured vertex
  texLayers.
- All three changes can be reverted independently; the rest of the
  capture/draw code is unchanged from the working baseline.

---

## 4. Trees rendered upside-down

### Symptom
Visible in same Wintertodt screenshot. Two trees on the snow have their
trunks pointing UP and leaves at the BOTTOM.

### Hypothesis
- Wrong orientation extraction. We use `m.getModelOrientation()` for static
  GameObjects and the engine-passed `orient` parameter for `drawDynamic`. If
  some animated trees have their orient packed differently (e.g., upper bits
  encoding non-Y rotation), our `(orient & 0x7FF)` mask drops information.
- OR a Y-axis flip bug specific to certain face windings.

### Next step
Find a tree that consistently renders upside-down. Get its model and OSRS
object ID. Compare orient values at runtime between stock (working) and ours.

---

## 5. Vertex buffer CPU↔GPU race — FIXED

### Status
Resolved by per-frame slot allocation in `SceneVertexBuffer`. The buffer is
sized for `FrameSync.FRAMES_IN_FLIGHT` contiguous slots of `BUFFER_BYTES`
each; `beginFrame` writes into `slot * slotBytes + dynamicOffset`, and
`recordDraw` binds with `offset = slot * slotBytes`. Static layers are
written once into slot 0 by `captureScene` and mirrored to the other slots
via `MemoryUtil.memCopy` (CPU-side, no GPU command). `captureScene` also
issues `vkDeviceWaitIdle` first because region changes can fire while
in-flight frames may still be reading the previous static layout.

Memory cost: ~144 MB total at current `MAX_VERTICES = 2_000_000` and
`FRAMES_IN_FLIGHT = 2` (was 72 MB).

---

## 6. Plugin disable second-cycle hang (intermittent)

### Symptom
Sometimes the second disable of the plugin in a JVM session hangs at
`shutdown step 4: resizeCanvas` (no "step 4 done" line in log). RuneLite
window stays open but unresponsive. Have to `kill` from terminal.

### Diagnostic data
- First disable in JVM: clean (full breadcrumb log to "step 4 done")
- Second disable: logs through "step 4: resizeCanvas" then nothing
- Suggests `client.resizeCanvas()` is blocking — likely AWT EDT deadlock,
  same family as issue #1

### Likely fix
Resolved by issue #1's offscreen-blit refactor (canvas state stays
GL-managed, doesn't accumulate Vulkan-WSI weirdness across cycles).

---

## Quick reference: what's in good shape

- Render correctness (texturing, lighting, fog, animated textures, terrain,
  most actors, most game objects, UI compositing)
- Per-pixel HSL→RGB decoding matching stock's `smoothBanding=false` look
- MSAA with alpha-to-coverage (handles transparent geometry without sorted
  alpha pass)
- UNORM swapchain + UNORM UI texture (no double sRGB encoding)
- Plugin enable/disable on first cycle (clean shutdown sequence with
  breadcrumb logging in `Disposables` and `GpuVulkanPlugin.shutDown`)
- Texture animations (water UV scroll per game tick)
- Roof culling for static scene geometry (works for the player-walks-under
  case, modulo the false-positive in issue #3)
- DPI-scaled viewport (clicking works correctly)
- Window resize (lazy swapchain rebuild via `SUBOPTIMAL_KHR`)

## Suggested priority for next session

1. **Wintertodt wall** — get object ID, target it precisely. Probably a
   small fix once identified. (issue #3)
2. **Offscreen + GL blit refactor** — fixes #1, #6, future-proofs canvas
   integration. ~1-2 days. (issue #1, #6)
3. **Per-frame vertex slots** — defensive correctness, audit cleanup.
   (issue #5)
4. **Trees upside-down** — separate investigation, lower urgency. (issue #4)
