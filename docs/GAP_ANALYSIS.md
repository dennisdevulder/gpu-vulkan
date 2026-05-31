# Gap analysis vs stock GPU plugin

Living document. Lists what stock OpenGL `GPU` does that we don't, with status.

Last updated: 2026-05-14.

## Status

| # | Gap                                             | Status        |
|---|-------------------------------------------------|---------------|
| 1 | Per-dynamic-model face sort (CPU)               | **Done (modulo priority interleave)** — all three dynamic paths (`drawDynamic`, `drawTemp`, `captureActors`) routed through `captureModelSorted`. Actor walk reuses the previous frame's cached `worldProjection`. Priority-bucket interleave not ported (only hits `RENDERMODE_SORTED_NO_DEPTH`). |
| 2 | Brightness gamma on untextured faces            | **Done** — `pow(rgb, brightness)` now applied to both HSL and textured paths in `scene.frag` before fog mix |
| 3 | `textureLightMode` (texture × HSL light blend)  | **Done** — new `brightTextures` config (matches stock's option name); `scene.frag` blends `mix(vec3(light), fullColor, textureLightMode)`; threaded via 32-byte fragment push (was 16). Reserved 12 bytes in `fragExtras` for future scene-frag uniforms |
| 4 | Entity vs scene dual-MVP                        | Open          |
| 5 | Smooth-banding mode                             | Open          |
| 6 | Colorblind filter                               | **Done** — new `ColorBlindMode` enum, `colorBlindMode` + `colorBlindIntensity` config keys; `colorblind.glsl` ported inline into `scene.frag` (runtime branch instead of `#if`); applied post-fog matching stock's order. Reuses `fragExtras.y` (mode) and `fragExtras.z` (intensity 0..1) push-constant slots |
| 7 | Near-plane geometry cull                        | **Mostly done** — dynamic sorted capture uses `ModelSorter`'s `p[2] < 50` reject (matches `FacePrioritySorter.java:145`), but falls back to unsorted emission when sorting rejects a transient model so projectiles / spotanims stay visible. Static side has no near-cull in stock either; the earlier `geom.glsl:~56-60` claim was a research-agent hallucination (no such file exists in stock's resources). |
| 8 | In-place scene mutation (farming/doors/trees)   | Open — needs per-zone re-upload; whole-scene re-capture on every `invalidateZone` tanks FPS in combat |
| 9 | Frame-to-frame depth-stencil sync hazard        | **Done** — `RenderPass` subpass dependency now declares prior color/depth writes; silences `WRITE_AFTER_WRITE` validation spam and the hit-X validation-layer SIGSEGV |
| 10| Exit-time Vulkan teardown                       | **Done** — JVM shutdown hook (`vkgpu-shutdown-watch`) now runs `vkDeviceWaitIdle` + `disposables.close()` against a static `activeInstance` reference. `draw()` gates on a `shuttingDown` flag to stop new frame submissions while the hook runs. Silences validation's "dispatch handle not found" at X-press. |

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
