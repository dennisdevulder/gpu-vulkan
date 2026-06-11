# Gap analysis vs stock GPU plugin

Living document. Lists what stock OpenGL `GPU` does that we don't, with status.

Last updated: 2026-06-11 (rows 13/14/15 closed; 14 had already landed in
5fe860f half an hour after the previous doc update).

## Status

| # | Gap                                             | Status        |
|---|-------------------------------------------------|---------------|
| 1 | Per-dynamic-model face sort (CPU)               | **Done** — all three dynamic paths routed through `captureModelSorted`. Priority-bucket interleave IS now ported (`ModelSorter.writePriorityOrder`, triggered by `RENDERMODE_SORTED_NO_DEPTH`, drawn via no-depth color pass + depth-only pass mirroring stock's `vaoPO`). Actors flow through `drawDynamic` with the engine-supplied projection — the cached-projection caveat is obsolete. |
| 2 | Brightness gamma on untextured faces            | **Done** — `pow(rgb, brightness)` now applied to both HSL and textured paths in `scene.frag` before fog mix |
| 3 | `textureLightMode` (texture × HSL light blend)  | **Done** — new `brightTextures` config (matches stock's option name); `scene.frag` blends `mix(vec3(light), fullColor, textureLightMode)`; threaded via 32-byte fragment push (was 16). Reserved 12 bytes in `fragExtras` for future scene-frag uniforms |
| 4 | Entity vs scene dual-MVP                        | **Reframed → see #11.** Confirmed still single `pc.mvp`, but for top-level scenes stock sets `entityProj = identity`, so the user-visible consequence is sub-worldview rendering (#11), not actor jitter. |
| 5 | Smooth-banding mode                             | **Done** — `smoothBanding` config threaded via `fragExtras.w` to `scene.frag`; semantics verified equivalent to stock `frag.glsl:85-88` |
| 6 | Colorblind filter                               | **Done** — new `ColorBlindMode` enum, `colorBlindMode` + `colorBlindIntensity` config keys; `colorblind.glsl` ported inline into `scene.frag` (runtime branch instead of `#if`); applied post-fog matching stock's order. Also applied to UI in `ui.frag`. |
| 7 | Near-plane geometry cull                        | **Mostly done** — dynamic sorted capture uses `ModelSorter`'s `p[2] < 50` reject (matches `FacePrioritySorter.java:145`), but falls back to unsorted emission when sorting rejects a transient model so projectiles / spotanims stay visible. Static side has no near-cull in stock either; the earlier `geom.glsl:~56-60` claim was a research-agent hallucination (no such file exists in stock's resources). |
| 8 | In-place scene mutation (farming/doors/trees)   | **Done** — `invalidateZone` → `DirtyZoneTracker`; `rebuildDirtyZones` re-emits only dirty zones into per-frame-slot overlay arenas; `SceneZoneDrawScheduler` masks superseded static ranges; full recapture only past a high-water mark. |
| 9 | Frame-to-frame depth-stencil sync hazard        | **Done** — `RenderPass` subpass dependency now declares prior color/depth writes; silences `WRITE_AFTER_WRITE` validation spam and the hit-X validation-layer SIGSEGV |
| 10| Exit-time Vulkan teardown                       | **Done** — JVM shutdown hook (`vkgpu-shutdown-watch`) now runs `vkDeviceWaitIdle` + `disposables.close()` against a static `activeInstance` reference. `draw()` gates on a `shuttingDown` flag to stop new frame submissions while the hook runs. Silences validation's "dispatch handle not found" at X-press. |
| 11| Sub-worldview (WorldEntity) rendering           | **Implemented (untested in-game).** `SubWorldViewManager`: one small `SceneRenderer` per worldview (own vertex arena — the fbf75b8 clobber class is structurally impossible), entity placement from the engine's per-zone `FloatProjection`, clip via CPU-composed `world*entity` MVP, fog via shader-side world-XZ reconstruction (`scene.vert` misc.yzw). Dynamic models flow through the same `ModelSorter` path with the engine-composed projection. Draw order: all opaque (toplevel then subs), then all blended alpha. The `isTopLevelScene` checks remain as ROUTING (toplevel arena vs per-worldview renderer), not drops. Remaining: in-game verification on sailing content; scene-level `entityTint` (ghost ships) still unimplemented — no push-constant space, needs a different slot. |
| 12| UI scaling filters                              | **Open.** Stock: NEAREST/LINEAR/MITCHELL/CATROM/XBR/HYBRID (`fragui.glsl`, `scale/*.glsl`). We always sample UI with LINEAR (`Texture.java:121-122`). Visible under stretched mode / HiDPI. |
| 13| `hideUnrelatedMaps` is a dead toggle            | **Done** — `prepareScene` now calls `RegionManager.prepare` (toplevel captures only, same as stock's `loadScene`). The old "too strong for a live Scene" caution was unfounded: stock runs the identical chunk math + identical `regions.txt` against the already-live scene at plugin start. Toggle takes effect on next scene load, matching stock (which has no config handler for it either). |
| 14| Draw distance doesn't cull geometry             | **Done** (5fe860f) — both phases draw only zones within `drawDistance + fogDepth + 2` tiles of the camera; `-Dvkgpu.fullSceneDraw=true` restores the old full draw. This row was already closed when the 2026-06-10 re-audit was written; the commit landed 29 minutes after the doc update. |
| 15| Fog edge ignores expanded map chunks            | **Done** — fog window edges now ride in a vec4 header prepended to the binding-1 UBO (`SceneUniforms.fogScene.xy`), written at scene capture from `client.getExpandedMapLoading()` with stock's formula `(-chunks*8+1)..(103+chunks*8)`. Header defaults to 1..103 before first capture. Sub-worldview captures don't touch it — fog is computed in toplevel space (scene.vert misc.yzw). |
| 16| Static alpha not depth-sorted per frame         | **Open (minor/accepted).** Stock re-sorts zone alpha geometry every frame (`Zone.java:520`); we replay static layers in capture order and lean on alpha-to-coverage. Can mis-order overlapping transparent statics. |

Minor shader deviations (footnote, not gaps): textured-face brightness applies
`pow` to the lightness term too (`scene.frag:151-154` vs stock
`pow(tex,b)*light`); stock's `fHsl` is `noperspective centroid`, ours is
default-smooth; texture alpha-test threshold 0.5 (NEAREST mag) vs stock's 1.0
at lod 0; stock freezes texture animation during loading, we always advance.
UI overlay-fade math differs: stock src-over `alphaBlend(ui, overlay)`, ours
`mix(ui.rgb, overlay.rgb, overlay.a)` — tints opaque UI instead of fading the
scene only.

## What we already have that stock doesn't

Don't accidentally regress these:

- MSAA + alpha-to-coverage (`ScenePipeline.java:112-127`)
- Reverse-Z depth (closer = larger z_ndc)
- Per-layer wireframe debug toggles (`GpuVulkanPluginConfig.java:112-167`)
- Per-frame roof culling via `hideRoofIds` (`SceneRenderer.java:63-150`)
- Per-face hard-cutout sentinel `vTrans == 255` (`scene.frag:47`)

## The gaps in detail

### 1. Per-dynamic-model face sort (CPU)

Stock does **not** use compute shaders. The first research agent hallucinated
`comp.glsl` / `comp_small.glsl`; stock's shader dir contains only
`vert/frag/vertui/fragui/colorblind/hsl_to_rgb.glsl`. The sort runs in Java,
once per dynamic model per frame, in
`runelite-client/src/main/java/net/runelite/client/plugins/gpu/FacePrioritySorter.java`.

Algorithm (`uploadSortedModel`, lines 89-455):
1. Project all model vertices through the `Projection` passed via
   `drawDynamic` / `drawTemp` — gives a camera-space depth `p[2]` per vertex.
2. Per-face depth bucket = `radius + avg(vertex depths)` → FIFO linked-list
   buckets in `[0, diameter)` (lines 162-202).
3. Back-face cull via screen-space cross-product sign (line 182).
4. Pack each face's vertex triple into a transient `vertexBuffer[]`
   (lines 239-260) holding pre-shuffled int data.
5. **No render priorities**: walk Z-buckets `maxFz..minFz` writing triples
   straight to `opaqueBuffer` or `alphaBuffer` based on per-face transparency
   (lines 267-274).
6. **With render priorities + prioritySort**: re-bin into 12 priority buckets
   and walk priorities 0-9 in order, with priority-10/11 faces interleaved
   based on per-bucket distance averages `avg12 / avg34 / avg68`
   (lines 278-451).

Our `SceneRenderer.captureModel` (line 478-577+) walks faces in model-
emission order, writes straight to the DYNAMIC buffer. No projection, no
Z-sort, no priority interleave.

**MVP scope for the port:**

- New class `ModelSorter` in `gpuvulkan` package — port of the algorithm,
  same constants (`MAX_VERTEX_COUNT=6500`, `MAX_FACE_COUNT=8192`,
  `MAX_DIAMETER=6000`), Vulkan-vertex-format output (our DYNAMIC layer
  vertex stride, not stock's int-packed format).
- `captureModel(Model, orient, x, y, z, Projection)` — new signature
  threading the engine's `Projection` (we already get it in `drawDynamic`
  / `drawTemp`).
- For `captureActors` (live NPCs/players, no engine-supplied Projection):
  build a Projection ourselves from `client.getCamera*` and the engine's
  pitch/yaw, or use a simple Euclidean-distance fallback.
- Single-buffer output, not opaque/alpha split — our pipeline uses
  alpha-to-coverage in one pass, so we keep one DYNAMIC stream but the
  sort still buys us correct priority order.

**What this does NOT need:** new descriptor sets, new pipelines, new SSBOs,
no compute work. The first agent's hallucination led us to plan a much
bigger structural change than reality requires.

**Status — what landed:**
- `ModelSorter.java` (new) — port of stock's `FacePrioritySorter`. Bucket
  sort by camera-space depth, back-face cull, near-plane reject at 50u.
  Same constants as stock.
- `SceneRenderer.captureModelSorted(Projection, Model, ...)` — uses the
  sorter, emits triangles back-to-front via `writeHslVert` (sorter pre-
  applies orientation + world translate). Sort rejects fall back to unsorted
  emission so transient effects do not vanish.
- `GpuVulkanPlugin.drawDynamic` and `drawTemp` now call
  `captureModelSorted` with the engine-supplied `worldProjection`.

**Status — what's left for #1:**
- Priority-bucket interleave (`prioritySort = true`) not ported — only
  affects `RENDERMODE_SORTED_NO_DEPTH` renderables, which we don't
  currently distinguish. Add later if visible bugs surface.
- Actor walk uses last frame's cached `worldProjection`. On a fresh
  scene with no prior `drawDynamic`/`drawTemp` callback yet, actors fall
  back to unsorted for one frame. Not user-visible.
- **Side benefit**: the sorter's `p[2] < 50` reject implements gap **#7
  (near-plane geometry cull)** for successfully sorted geometry going through
  `captureModelSorted`. Models rejected by sorting fall back to unsorted
  emission to preserve transient visibility. Static scene capture still has no
  near-cull, so #7 stays partially open.

### 2. Brightness gamma on untextured faces

`scene.frag:64-65` applies `pow(rgb, brightness)` only on the textured
branch. Untextured HSL output (line 60) skips the gamma. Stock applies it
to all colour output (`vert.glsl` brightness exponent).

### 3. `textureLightMode`

Stock has a uniform that blends per-face HSL light with the texture's own
RGB (`frag.glsl:81`). We hardcode mode 0 (`scene.frag:69-70`).

### 4. Entity vs scene dual-MVP

Stock uses separate `entityProj` + `worldProj` matrices in `vert.glsl`.
We use a single `pc.mvp`. Suspected impact: actor sub-pixel jitter vs
terrain at distance.

### 5. Smooth-banding mode

Stock `frag.glsl:86` `smoothBanding` uniform toggles between per-fragment
HSL decode (banded, default) and linear-RGB interpolation (smooth). We only
do the banded path.

### 6. Colorblind filter

`colorblind.glsl` in stock with protan/deuteran/tritan modes. We have none.

### 7. Near-plane geometry cull — MOSTLY RESOLVED

Original claim was based on a research-agent hallucination: stock has no
`geom.glsl` (verified by `ls` of stock's resources directory). The only
`< 50` near-plane check anywhere in stock is in
`FacePrioritySorter.java:145`, which rejects whole dynamic models when
any vertex projects to z < 50. We mirror this in `ModelSorter.sort()`,
then fall back to unsorted emission if sorting rejects a transient model.

Static-scene near-plane culling: stock doesn't have it. With
`Mat4.projection(w, h, 50)`, the "50" is a depth-scale, not a near plane —
the projection's natural frustum clip kicks in only at `z = 0.01`. Camera
clipping into a wall shows inside faces in both stock and our renderer.

Nothing else to port unless the fallback produces visible near-plane artifacts.
