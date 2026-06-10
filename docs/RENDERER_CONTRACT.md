# GPU Vulkan Renderer Contract

This document defines the baseline contract for performance work. A change is
not a renderer improvement unless it preserves these rules in normal gameplay.

## Frame Ownership

- The CPU scene/model emission path is the playable baseline and the oracle for
  future offload work.
- A renderable/model is emitted by exactly one owner per frame. It must never be
  emitted by both CPU and GPU replacement paths.
- Experimental compute/debug/probe paths must not affect normal scene output
  unless a class-specific parity gate explicitly enables them.

## Inputs

- Static scene capture consumes the current `Scene`, tiles, bridge tiles,
  roof/tile flags, object renderables, model orientation, and world position.
- Dynamic capture consumes the engine callback model, projection, orientation,
  world position, render mode, and actor/dynamic classification.
- Frame rendering consumes camera/projection state, draw distance, fog,
  brightness, texture animation tick, UI pixels, overlay tint, and draw-pass
  boundaries.

## Outputs

- Static and dynamic geometry is emitted as Vulkan scene vertices with the same
  transformed positions, HSL/RGB lighting, texture layer, UV, alpha, and bias
  metadata as the CPU baseline.
- Sorted, transparent, and priority models preserve the CPU ordering contract.
- Dirty-zone overlay geometry replaces the corresponding static zone for the
  active frame slot without mutating unrelated zones.
- UI is uploaded and composited above the 3D scene with normal RuneLite alpha
  behavior.
- Screenshot readback must work unless a benchmark-only system property
  explicitly disables it.

## Invariants

- Actors and player/NPC models must animate from current-frame model data.
- Mutable objects such as farming patches, doors, chests, and animated scenery
  must not be cached as stale static meshes.
- Dropped items, projectiles, spot animations, water, translucent effects, and
  priority renderables must not hide, duplicate, or reorder unrelated geometry.
- Roof hiding applies only to the intended upper-plane ranges; current/lower
  plane geometry remains visible.
- Any performance branch must add or preserve metrics that explain the win:
  command counts, vertices, upload bytes, readback bytes, CPU timing, or model
  emission timing.

## Extension-Owned Passes

- A `ScenePassRedirect` extension owns the scene's color output for the
  frame: it must end every pass it begins, leave no pass open on entry to or
  exit from `recordAfterScene`, and draw a full-viewport resolve in
  `recordResolve` (the UI composites on top in the same pass).
- Pipelines used inside an extension-owned `RenderTarget` pass must be
  created from that target's `device()`; pipelines from the main device are
  only valid in the final on-screen pass.
- Targets are not transitioned for sampling automatically — call
  `prepareForSampling` after the pass that wrote them, outside any pass.
- When `RenderTarget.resize` returns true, every bind group referencing that
  target is stale and must be recreated before the next draw using it.

## Post-Composite Hook

- `recordAfterComposite` runs after the final render pass has ended and
  before the frame is submitted/presented, on the graphics queue's command
  buffer. The frame's in-flight fence covers everything recorded here.
- `VulkanPostFrameContext.colorImage()` is backend-owned (swapchain image on
  the KHR present path, offscreen target on the macOS custom-present path),
  created with `TRANSFER_SRC` usage. Its format is the swapchain/surface
  format — do not assume RGBA8 ordering.
- The image is in `imageLayout()` at hook entry; any transition recorded by
  the hook must restore that exact layout before returning, or present will
  consume an image in the wrong layout.
- Do not begin a render pass against the image and do not stall the
  graphics queue from inside the hook (no fence waits, no submits). Copy
  out, barrier back, return. Heavy work belongs on another queue or thread,
  fed by per-`frameIndex()` resources.

## Video Encode Queue

- When the device exposes `VK_QUEUE_VIDEO_ENCODE_BIT_KHR` plus
  `VK_KHR_video_queue`/`VK_KHR_video_encode_queue` and at least one codec
  extension (H.264/H.265/AV1), the backend enables those extensions and
  creates a dedicated encode queue at device creation.
  `VulkanEncodeContext.isAvailable()` reports it; `unavailableReason()`
  explains every refusal.
- The encode queue is never the graphics queue: a shared-family device gets
  a second queue from the family (priority 0.5), and if the family has only
  one queue no encode queue is created.
- The backend submits nothing to the encode queue. A consumer plugin owns
  all video session objects, submissions, and synchronization on it, and
  must perform queue-family ownership transfers when moving images between
  the graphics and encode families.
- `-Dvkgpu.disableVideoEncode=true` turns the whole path off (escape hatch
  for broken drivers); the renderer must behave identically with it set.

## Offload Gate

GPU offload may only replace CPU emission after a per-class parity record proves
that the candidate emits the same contract fields as CPU. The class order is:
static opaque scene models, dynamic opaque objects, projectiles/effects,
sorted/transparent/priority models, and actors last.
