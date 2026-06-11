# gpu-vulkan — working agreement for Claude

Project context: Vulkan-based RuneLite GPU plugin. Works on Linux. The macOS MoltenVK flicker is **no longer an active issue** (user, 2026-06-10). The active problem is the **Windows/NVIDIA Olm stale-renderables bug** (see section below).

## How to work here — non-negotiable rules

These exist because past sessions degraded into speculation-driven busywork. Read before touching code.

### 1. A failed fix is data. Update the model, don't re-guess.

When a change you made does NOT resolve the user's reported symptom, treat the lack of resolution as a hard signal that your causal model is wrong. Do not propose a sibling fix in the same hypothesis family. Stop, write down what the failure rules out, then pick a different hypothesis family.

Anti-pattern: "vec3→vec4 didn't fix it, let me try per-draw push constants" → "that didn't fix it, let me try fence waits" → "that didn't fix it, let me try layer hierarchy." That's pattern-matching plausible Vulkan bugs onto a symptom you haven't actually localized. Each individual fix may be correct, but stacking them is not problem-solving.

### 2. Never delegate diagnosis to log-collection.

Do not add `log.info(...)` lines and ask the user to run the build and paste the output. Do not add diagnostic counters, per-frame size dumps, or "let's see what these values look like" instrumentation. The user has limited patience for log-reading; they have hired you to do the analysis.

If you genuinely need a runtime value, derive it from the code (read the relevant call sites and the OSRS API surface) or use validation/RenderDoc. If you can't, say "I can't determine this without a diagnostic build — should I add temporary logging?" and let the user say no.

### 3. Don't ask clarifying questions when the system reminder says not to.

The session may include `work without stopping for clarifying questions`. Honor it. Make the reasonable call and ship.

### 4. Validation is a tool, not a deliverable.

If validation is silent under the failure conditions, the bug is not in Vulkan API usage. Stop looking for Vulkan API bugs. The bug is in: MoltenVK→Metal translation, the AWT/AppKit integration, the swapchain/present timing, or the shader semantics on Apple GPUs. Pick one and investigate.

### 5. Don't ship "small unrelated fixes" alongside the main investigation.

If the user reports symptom X and also mentions Y, Z as "probably unrelated," treat Y and Z as part of the same root cause until proven otherwise. Correlated symptoms usually share a cause. Don't fix Y and call it progress on X.

### 6. Engineer real solutions, not patches.

If a fix is structural (rewriting how a layer is composed, how present is invoked, how a buffer is sized), it's an engineering change — get user buy-in before making it. If a fix is a patch (a flag, a workaround, an extra wait), it should be reversible and tightly justified. Don't accumulate patches; the user has explicitly said "stop trying to fool around and engineer a proper solution."

### 7. Direct, terse responses. No motion-as-progress.

The user can read diffs. They do not need a recap of what you just did unless they asked. End-of-turn summary is one or two sentences max. No "let me know if…" filler. No "this should…" speculation — say "I changed X. Run it." or "I'm not changing anything yet — here's what I think is happening." Both are fine; padding isn't.

### 8. The user's "still broken" reports are facts, not opinions.

When the user says the flicker is unchanged after your fix, the fix didn't address the cause. Don't second-guess them, don't ask for screenshots to verify, don't suggest they look more carefully. Update the model (rule 1).

## What we know about the macOS problem (running notes)

Maintained across sessions. Append, don't rewrite.

**Ruled out** (verified with the user / validation / log inspection):
- Vertex attribute alignment (vec3 padded to vec4) — applied, didn't fix flicker
- MoltenVK push-constant reuse (#2483) — per-draw push applied, didn't fix flicker
- Host-memory races on vertex buffer + UI staging — fence waits applied, validation silent, didn't fix flicker
- Swapchain `oldSwapchain` handover / minImageCount — explicit `minImageCount=3` + `vkDeviceWaitIdle` before destroy applied, didn't fix flicker
- CAMetalLayer hierarchy (was sibling subview of contentView; now sublayer of canvasLayer) — applied, fixed/expected to fix the sidebar/cursor side bugs, did not fix the layer-flicker symptom
- AWT canvas vs OSRS engine canvas dimension mismatch — confirmed identical via diagnostic log
- Vulkan API correctness in general — sync validation silent under the failure condition

**Still suspect** (no evidence either way yet):
- MoltenVK's internal `vkQueuePresentKHR` → `[id<CAMetalDrawable> present]` timing on Apple Silicon. The async present queue may hand a drawable to the compositor before the GPU has finished writing it, or hand back a stale drawable.
- CADisplayLink's `[CATransaction flush]` cadence interacting with MoltenVK's async present.
- Shader semantics on Metal vs Linux Vulkan: reverse-Z + alphaToCoverage interaction; depth-comparison signedness; varying-precision differences. Less likely given the scene visually renders correctly otherwise.

**User-confirmed symptoms** (do not lose track of these):
- Layer-level flicker: "one layer covering/uncovering" tied to zoom level, not pixel tearing
- Persistent across all the fixes above
- Linux path with the same source: no flicker

**New suspect (2026-06 review, untested)**: MoltenVK async queue submits (`MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS=0` is the modern default) mean `vkQueueSubmit` can return before the render MTLCommandBuffer is committed; `MacOSMetalHelper.presentDrawable()` then commits its own present command buffer to the same MTLCommandQueue, which can land *before* the render commit — drawable presents stale content. Falsification test: set `MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS=1` (or CPU-wait the in-flight fence before `nPresentDrawable`); if flicker stops, this is it. No `MVK_CONFIG*` is currently set anywhere in the repo.

## Windows/NVIDIA Olm stale-renderables bug (2026-06 review findings)

**Status 2026-06-10: all four suspects fixed in working tree, plus a fifth found during the fix** — mid-frame `captureScene` left the write cursor in the static arena; if no zones were dirty, that frame's dynamic capture wrote the static arena while `recordDraw` read never-written frame-arena bytes (uninitialized on NVIDIA, zeroed on Mesa). `rebuildDirtyZones` now restores the frame arena unconditionally. Awaiting user Olm retest.

**Partial confirmation 2026-06-10 (macOS):** farming-patch rake now updates correctly (weeds disappear) — validates the zone-rebuild masking fix (suspect #2, platform-independent). Does NOT validate the NVIDIA-specific suspects (stale dynamic-range replay, uninitialized frame-arena reads) — those need the Windows/NVIDIA Olm retest.

## Travel-return FPS loss — RESOLVED 2026-06-10: engine-side, by design

Controlled test (Linux RX 6900 XT, stock GPU plugin): boot 500-600 FPS at cook
spot → same travel circle → return → ~420. The loss reproduces on STOCK, same
order of magnitude proportionally. Mechanism: after traveling, the engine's
cached neighboring-region data fully populates the extended scene on rebuild —
the revisited Scene genuinely contains ~2x renderables (our capture logs:
gameObjects 1.4M → 3.0M). Expanded map loading working as designed; affects
every renderer; restart "fixed" it only because a fresh client has an empty
region cache. NOT a gpu-vulkan bug. Felt severe on Vulkan only because our
baseline FPS is lower (see next section). Do not reopen without new evidence.
(Side notes from the chase: 117HD coexistence guard added in 4928780; one
transient session of order-dependent renderer FPS was never explained and
never reproduced — vsync/swap-interval interaction suspected, untraceable.)

## Performance vs stock GPU — RESOLVED 2026-06-10 (Linux RX 6900 XT)

Morning baseline: stock ~550-600 FPS, ours ~170 (default two-pass alpha).
Evening: ~390-500 FPS. What closed it, in measured order of impact:
1. **Alpha-face split** (869f2cb): blended pass replayed the whole static
   scene; now draws only a STATIC_ALPHA layer. 170 → ~320. Single-pass-alpha
   mode then measured equal-or-worse and was removed (d173d55).
2. **Zone frustum culling** (107d895): the engine only invokes ~20 zones for
   stock; we drew the whole radius square (~1.7M verts/frame, mostly behind
   the camera). Gribb-Hartmann planes from the pass MVP. ~320 → ~500.
   Escape hatch: -Dvkgpu.disableFrustumCull=true.
3. Skybox drawn last instead of first (519d3c4) — overdraw.
4. UI upload (~0.5ms/frame) is content-churn-bound (overlay plugins dirty
   most rows); the dirty-row diff in GfxStreamingImage is already optimal.
5. VRAM mirror (870ea0b) no-ops on this box (ReBAR confirmed, flags 0x7) —
   it targets non-ReBAR machines.

Diagnosis lessons (paid for, don't repeat): the per-second `recon` stats
line and the debug overlay are the ground truth — three plausible theories
(MSAA, draw-call count, memory residency) died against measurements; the
real causes were found by reading `zoneOpq` (engine zone count) and
`drawVerts/scene`. ALSO: a black screen with `preSD=0` + `ui=0.00` means
the Benchmark "Skip UI upload" toggle is on and the user is staring at an
invisible login screen — check config before suspecting commits.

## Sub-worldview rendering (branch feat/sub-worldview-rendering)

First live validation 2026-06-10 (Linux, sailing hub plugin, Port Sarim): worldviews 11 and 2830 created/freed cleanly, ship sails visible on water. Static arena growth fix (42a2c69) and zone culling (5fe860f) also on this branch and confirmed working in the same session. 2026-06-11: the "sails without hulls" detail was real, and had TWO causes. (1) 5fe860f's radius cull centered the zone window on the toplevel camera inside sub renderers' local zone grids — fixed in 0ab7ed93 (sub renderers skip radius cull, frustum cull suffices). (2) The actual root cause: sub-worldview tile arrays are worldview-sized (stock sizes its context from `worldView.getSizeX()>>3`), so `captureTiles`'s `canCoverScene(regular, 0, 104)` check always failed for ship scenes and `captureSceneOnce` silently returned — sub statics were NEVER captured; only dynamics (sails/crew) drew. CAUTION on validation evidence: the first "hull renders fine" confirmation was taken while stock GPU was co-running and rendering the hull itself. Hull confirmed rendering 2026-06-11 (d6234ae5, retested with gpu-vulkan as sole renderer).

**LIKELY ROOT CAUSE FOUND 2026-06-11 (61f7c6ac7), on Linux this time:** frozen
projectiles/gas/floor artifacts reproduced on Linux during boss fights. Static
and overlay-zone capture accepted ANY tile renderable via a `getModel()`
catch-all; stock whitelists only Model + DynamicObject
(`SceneUploader.zoneRenderableSize`). Transients crossing a zone mid-rebuild
(fights invalidate zones constantly) were baked frozen into the overlay
capture and persisted until the next rebuild — possibly never after the fight.
`resolveModel` now mirrors stock's whitelist. This is symptom-identical to the
Olm report; the Windows/NVIDIA retest should re-run on a build with 61f7c6ac7.
Earlier ranked suspects below kept for history:

Symptom: projectiles/ground items persist in Olm arena on Windows/NVIDIA, not on Linux/Mesa. Ranked code-cited suspects:
1. `draw()` without `drawScene` replays stale dynamic ranges from rotated frame slots (`GpuVulkanPlugin.java:859` sole `beginFrame` site; `SceneRenderer.java:1052-1057`). Fix existed in 8c01644, reverted wholesale by 8220142 (revert coupled it to the broken 6085067 zone change — they were independent). NVIDIA visibility: unlocked FPS + IMMEDIATE/MAILBOX emits many stale presents; Mesa FIFO blocks, hides it. Confirm via existing `DrawCallbackStats`: `frames > drawScene`.
2. `SceneZoneDrawScheduler.hasOverlayRange` (:295-299) requires per-layer `count > 0` — zone rebuilt to *empty* layer never masks static range → despawned objects draw forever. Platform-independent, deterministic.
3. Overlay arena (`overlayNextVertex[slot]`) grows monotonically; on overflow rebuilds silently dropped but `markSlotRebuilt` still called (`SceneRenderer.java:298-306,387`).
4. `sceneIdentityChanged` (`GpuVulkanPlugin.java:982-992`) ignores Scene reference / instance template chunks — same-base instance rebuild skips recapture (upstream compares templates, `GpuPlugin.java:1656-1701`).

## Constraints (already established by the user, do not re-litigate)

- Conventional commit style for commit messages
- LLM attribution: follow the Linux kernel convention. For commits where an assistant materially shaped the code (new files, multi-file refactors, design decisions), add an `Assisted-by:` trailer below the human `Signed-off-by:` line — e.g. `Assisted-by: Claude Opus 4.7`. Trivial autocomplete doesn't need a trailer. Never use `Co-Authored-By:` — that implies joint authorship, which an LLM cannot be.
- "we depend on runelite we can't just add random shit and hope they're okay with it" — no upstream-incompatible patches
- CPU readback is unacceptable on 1 thread available
- Stop trying to fool around and engineer a proper solution
