# Production-Readiness Review — GPU (Vulkan) RuneLite Plugin

Reviewed against NASA's *Power of Ten*, common Java best practices, the RuneLite
plugin-hub rules, and Vulkan best practices. Scope: `src/main/java` (73 files,
~14,800 LOC), shaders, build, and repo layout. This is a review document only — no
source was changed.

## 1. Executive summary

This is a well-engineered codebase. The Vulkan layer in particular is a genuine
strength — resource lifecycle, synchronization, and error handling are done to a
standard most hand-written Vulkan code never reaches. There are no correctness
ship-blockers found at the API level.

The gaps that stood between this and "hand it to an external reviewer" were
mostly about **form, not function**. The zero-behavior-change release hygiene
items have since been handled in-tree; the remaining work is verification and
packaging discipline, not renderer optimization.

| # | Finding | Severity | Effort | Regression risk |
|---|---------|----------|--------|-----------------|
| A | `SceneRenderer.java` is 3520 lines / ~70 methods / 4 inner classes | High | High | Med–High |
| B | 65 of 73 source files had no license header (hub convention) | Done | Low | None |
| C | Near-zero test coverage (1 instantiation test) | High | Med | None |
| D | `runelite-plugin.properties` `support=` was a placeholder URL | Done | Trivial | None |
| E | `GpuVulkanPlugin.java` 1069 lines; `startUp()` ~315 lines | Med | Med | Low |
| F | Several functions exceed NASA's 60-line rule | Med | Med | Low–Med |
| G | No `@Nullable`/`@Nonnull`; null-as-sentinel throughout | Low | Low | None |
| H | Scattered magic numbers (viewport/brightness defaults) | Low | Low | None |
| I | Working docs + crash dumps cluttered the repo root | Done | Trivial | None |

Severity here means "how much a reviewer will care," not "how broken it is." Nothing
in this table is a bug.

## 2. What's already good — keep it, don't touch it

A reviewer should be pointed at these so they don't waste time re-deriving that the
Vulkan layer is sound.

- **Deterministic teardown.** `Disposables` is a LIFO close-stack
  (`Disposables.java:19-30`) — registrations happen in creation order, `close()` pops
  in reverse, which is exactly the destroy-children-before-parents order the Vulkan
  spec requires. Every `vkCreate*`/`vkAllocate*` has a matching destroy/free.
- **Every result code is checked.** `Vk.check(String, int)` (`Vk.java:22`) converts
  non-success `VkResult` into an exception at the boundary — no raw `VK_*` codes
  propagate upward.
- **Synchronization is correct.** Triple-buffered frames-in-flight
  (`FrameSync.java:27` `FRAMES_IN_FLIGHT = 3`), per-frame `imageAvailable` + `inFlight`
  and per-*image* `renderFinished` semaphores, fences created signaled so frame 0
  doesn't deadlock. No `vkDeviceWaitIdle` in the per-frame path — the only uses are in
  swapchain recreate/destroy (`Swapchain.java:87,104,305`), which is correct.
- **Native memory discipline.** `MemoryStack` try-with-resources is used consistently
  for transient structs; long-lived `memAlloc` (SPIR-V) is freed after module creation.
- **Swapchain handover is spec-correct**, including a documented MoltenVK landmine
  workaround (`Swapchain.java:84-104`). The macOS "why" comments here and at
  `Swapchain.java:252` are load-bearing — **do not delete them** during any cleanup.
- **RuneLite compliance.** Proper `@PluginDescriptor` (`GpuVulkanPlugin.java:34-40`,
  `enabledByDefault=false` is the right call for a new renderer), `@ConfigGroup`
  config, SLF4J via `@Slf4j` (no `System.out`), and only public `net.runelite.api.*`
  surface — no deobfuscated client internals, no reflection, no networking, no
  arbitrary file I/O. The `gfx/` interface/impl split is deliberate (see below).

## 3. Modularity — the primary issue

### A. `SceneRenderer.java` (3520 lines) — **High**

One class owns: vertex-buffer arena allocation, model-mesh caching, per-layer scene
capture (terrain/wall/object/overlay), face-UV computation, priority-sort emission,
draw-call recording, and low-level vertex packing. That is six or seven
responsibilities. A reviewer cannot hold this file in their head, and it is the single
biggest "this isn't done yet" signal in the repo.

The decomposition below is the recommended target. It is split into two risk classes —
do the first class freely; gate the second.

**Pure-helper extraction (zero behavior change, diff-verifiable).** These methods are
side-effect-free statics that depend only on their arguments + `MemoryUtil`. Moving
them out is a call-site-only diff and removes ~600–800 lines:

- `VertexWrite` — `writeFloatArray`/`writeIntArray`/`writeShortArray`/`writeByteArray`
  (`SceneRenderer.java:658-716`), `align4` (`:623,628`), `floatBytes`/`intBytes`/
  `shortBytes`/`byteBytes` (`:638-653`), `addAligned` (`:633`).
- `FaceCounts` — `countTransparentFaces`/`countTexturedFaces`/`countNonZeroBytes`
  (`:2538-2574`).
- `TileGeometry` — `isBridge`/`hasTileFlag`/`renderLevel`/`clamp`/`tileRoofIdAt` and
  the `RoofInfo` record (`:3168-3224`).
- `FaceUv` — `computeFaceUvs` (`:2606`, ~240 lines). Carry its embedded BSD-2-Clause
  attribution block with it.
- `Hsl` — the HSL/RGB packing helpers in the `writePackedVertex*` family (`:3363-3399`).

**Structural extraction (touches behavior — needs buy-in, higher risk).** Order
A→B→D→C, easiest/safest first:

1. `ModelCache` — the mesh arena + `ModelCacheEntry` (`modelInfo`/`clearModelCache`,
   `:464-550`). Owns its own buffer; clean seam.
2. Draw-call recording — `drawStaticPlane`/`drawOverlayPlane`/`drawRange`/
   `drawWithSkips`/`drawPriorityRanges` (`:3044-3304`).
3. **Per-layer scene capture — do this LAST and behind verification.** The
   `capture*` family (`:1367-1797`, `captureModelCullOnlyFused` at `:2304`) owns the
   shared write cursor (`useStaticWriteArena`/`useFrameWriteArena`/`setWriteCursor`,
   `:433-454`) and the `docs/RENDERER_CONTRACT.md` invariants. This is the
   macOS-flicker-adjacent emission path. Until the MoltenVK flicker is root-caused,
   any reshuffle of this code risks masking or moving the bug. **Recommendation:** split
   it only behind a frame-for-frame Linux/macOS A/B comparison, ideally after the
   flicker is understood — not as a blind tidy-up.

### E. `GpuVulkanPlugin.java` (1069 lines) — **Med**

`startUp()` is ~315 lines mixing Vulkan bootstrap, extension registration, overlay
wiring, and event setup. Two seams:
- Extract a bootstrap helper for the Vulkan/renderer construction sequence — but the
  `Disposables` registration order must stay byte-identical (teardown correctness
  depends on it), so this is low-*medium* risk, not zero.
- Extract the stats-formatting helpers (`compactCount`/`mib`/`avgMs`-style unit
  conversion around `:897-913`) into a tiny `StatsFormat` utility — these are pure and
  trivially testable.

### `VulkanRenderer.java` (727 lines) — **Low priority**

Frame recording, present, and render-pass orchestration are mixed. There is a clean
`FrameRecorder` vs `Presenter` seam, but this file is coherent enough that it's the
lowest-value split. Note it; don't prioritize it.

### Package layout — leave flat (a deliberate design)

Do **not** reorganize into layered packages. The flat `gpuvulkan` package with
package-private `final` classes is load-bearing: the `gfx/` subpackage exposes public
interfaces, and the `Gfx*` implementations are intentionally package-private so they can
touch package-private `Texture`/`Buffer` without widening those to `public`. A layered
reorg would force a real public-API expansion — that's a regression in encapsulation, not
an improvement. The two existing subpackages (`gfx/`, `regions/`) are the right amount of
structure.

## 4. NASA "Power of Ten" — adapted to a renderer

The *Power of Ten* targets flight-control C. Several rules map cleanly to this code;
some do not, and it's worth being explicit rather than cargo-culting all ten.

- **Rule 4 — function ≤ 60 lines: violated in a handful of places.** Worst offenders:
  `GpuVulkanPlugin.startUp()` ~315, `SceneRenderer.computeFaceUvs` ~240,
  `SceneRenderer.captureModelCullOnlyFused` ~230. The §3 extraction work resolves most
  of these as a side effect. Where a long function is a genuinely atomic command-record
  sequence, a one-line justification comment is acceptable instead of a forced split.
- **Rule 3 — no dynamic allocation after init: effectively satisfied, call it a pass.**
  The per-frame path writes into persistently-mapped buffers and pre-sized arenas
  (`MAX_STATIC_VERTICES`, `MAX_FRAME_VERTICES` in `SceneRenderer`), and transient native
  structs use `MemoryStack`. Worth a sentence in any reviewer-facing doc, because it's a
  real strength. Spot-check that no `*.calloc()` (non-stack) sits inside the draw loop.
- **Rule 5 — check every return value: satisfied for Vulkan** via `Vk.check`. The gap is
  *parameter* assertions at public/seam boundaries (e.g. capture entry points assuming
  non-null scene arrays). Adding a few guard checks at the new class boundaries created
  in §3 would close this without runtime cost in the hot path.
- **Rules 1 (no recursion / simple control flow) and 2 (bounded loops):** mostly fine;
  note `resolveModel` recurses through `Renderable` wrappers (`SceneRenderer.java:1683`)
  — bounded in practice, but worth a depth guard if it's ever fed untrusted data.
- **Rules aimed at embedded C (no heap after init, no function pointers, preprocessor
  limits): partially N/A** on the JVM. Don't manufacture findings to satisfy them.

## 5. Java best practices

- **License headers — done.** Source files now carry BSD-2-Clause headers, with
  existing vendored-attribution blocks preserved.
- **Nullness (Low).** No `@Nullable`/`@Nonnull` anywhere; null is the
  "not-initialized"/"not-found" sentinel throughout (e.g. `resolveModel` returns null,
  `SceneRenderer.java:1659`; `msaaColor` null when MSAA off). Annotate the boundaries so
  tooling can lint them. **Do not** convert hot-path null sentinels to `Optional` — the
  per-frame allocation isn't worth it.
- **Magic numbers (Low).** Viewport defaults (`1`), default skybox (`0x000000`),
  brightness `0.7f`, draw distance `90`, fog `30` in `VulkanRenderer` (~`:69-82`) read as
  bare literals. Promote to named constants; they're harmless but they make a reviewer
  pause.
- **Comment hygiene.** Most comments are good. Apply the standard triage: delete
  retrospective ("we used to…") and session-debug notes, keep the spec/landmine "why"
  comments. The MoltenVK comments in `Swapchain.java` are the canonical *keep* — they
  warn about a non-obvious driver behavior a future editor would otherwise re-break.

## 6. RuneLite plugin-hub compliance

**Passes:** `@PluginDescriptor` and `@ConfigGroup` present and well-formed; no forbidden
APIs (no reflection, sockets, HTTP, or arbitrary file I/O — only classpath resource
loads for shaders); public RuneLite API only.

**Fixed before submission:**
- `runelite-plugin.properties:3` now points at the real issue tracker.
- License headers (§5) are present on source files.

## 7. Testing

`GpuVulkanPluginTest` (32 lines) only constructs the plugin. There is no coverage of any
logic. GPU output is hard to unit-test, but the *pure* helpers are not — and once §3's
Tier-1 extraction makes them top-level classes, they become trivially testable. JUnit 4
is already wired (`build.gradle`), so this is purely additive:

- `Mat4Ops` — projection/view matrix math (golden-value tests).
- `VertexWrite` — byte layout and `align4` edge cases.
- `Hsl` — HSL↔RGB packing round-trips.
- `FaceCounts`, `TileGeometry` — counting and roof/bridge predicates.
- region math (`regions/`) and the stats-formatting helpers (`StatsFormat`).

Sequencing matters: extract the helpers (Tier-1, behavior-preserving) **first**, write
these tests to lock current behavior, **then** attempt any structural split (§3 Tier-3).
The tests become the regression net for the risky moves.

## 8. Repo shippability

- **Working docs moved.** `GAP_ANALYSIS.md`, `COMPATIBILITY_MATRIX.md`,
  `KNOWN_ISSUES.md`, and `RENDERER_CONTRACT.md` now live under `docs/`.
- **Crash dumps deleted.** Local `hs_err_pid*.log` / `replay_pid*` artifacts were
  removed from the repo root.

## 9. Prioritized roadmap

Risk-ordered so it can be executed incrementally, each tier independently shippable.

**Tier 1 — release hygiene (done):**
1. Add BSD-2-Clause headers to files missing them; preserve vendored blocks.
2. Fix `runelite-plugin.properties` `support=` URL.
3. Move the four working docs into `docs/`; delete local crash dumps.
4. Remove stale compute-prototype code and shader paths that no longer ship.

**Tier 2 — production-readiness gates (next):**
5. Run `./gradlew test`, `./gradlew build`, `./gradlew pluginJar`, and
   `./gradlew shadowJar` on Temurin 21.
6. Manually smoke-test enable/disable, login, world hop, resize, sidebar
   collapse, screenshots, MSAA off/on, validation off, and macOS layer behavior.
7. Pin or document the RuneLite dependency version used for the release build so
   the produced artifact is reproducible.
8. Add small tests for pure utilities as they are extracted or touched. Do not
   refactor hot renderer structure just to chase coverage.

**Tier 3 — post-release maintainability, not production blocking:**
9. Extract pure helpers from `SceneRenderer` only when the diff is mechanically
   reviewable and tests exist.
10. Split `GpuVulkanPlugin.startUp()` only if the `Disposables` registration order
   stays identical.

Each finding above carries its file:line, severity, and regression risk so the work can
be picked up piecemeal without re-deriving the analysis.
