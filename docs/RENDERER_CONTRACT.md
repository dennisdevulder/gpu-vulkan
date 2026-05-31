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

## Offload Gate

GPU offload may only replace CPU emission after a per-class parity record proves
that the candidate emits the same contract fields as CPU. The class order is:
static opaque scene models, dynamic opaque objects, projectiles/effects,
sorted/transparent/priority models, and actors last.
