# gpu-vulkan — working agreement for Claude

Project context: Vulkan-based RuneLite GPU plugin. Works on Linux. The active problem is **macOS-only rendering bugs via MoltenVK** (Apple Silicon M2 Pro). Validation layer has been run; it is silent under the failure conditions.

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

## Constraints (already established by the user, do not re-litigate)

- Conventional commit style for commit messages
- Never add AI attribution / Co-Authored-By
- "we depend on runelite we can't just add random shit and hope they're okay with it" — no upstream-incompatible patches
- CPU readback is unacceptable on 1 thread available
- Stop trying to fool around and engineer a proper solution
