# GPU (Vulkan) Release Checklist

This checklist is for production readiness. It deliberately excludes renderer
optimization work.

## Build gates

- `JAVA_HOME=/path/to/temurin-21 ./gradlew test`
- `JAVA_HOME=/path/to/temurin-21 ./gradlew build`
- `JAVA_HOME=/path/to/temurin-21 ./gradlew pluginJar` — once with
  glslangValidator installed (regenerates + freshness-checks the committed
  SPIR-V), once without (hub-builder simulation).
- `JAVA_HOME=/path/to/temurin-21 ./gradlew shadowJar`
- On the Mac: `./gradlew compileMacOSMetalHelper` and commit the regenerated
  `librlmtl.dylib` (JNI symbols encode the package; it must be built from
  the release commit's `rlmtl.m`).
- Validation-layer run (`-Dvkgpu.validation=true`) stays clean for the smoke
  list below.
- Confirm `runelite-plugin.properties` has a real `support=` URL.
- Confirm release artifacts are not tracked under `build/`.
- Confirm no `hs_err_pid*.log`, `replay_pid*`, or ad-hoc debug artifacts are in
  the working tree.
- Pre-submission checks in [SUBMISSION.md](SUBMISSION.md) all pass.

## Manual smoke test (each supported platform)

- Enable plugin from a clean RuneLite profile.
- Log in and render a busy scene for at least five minutes.
- Zone-cull regression: walk Lumbridge → Varrock; no missing or popping
  geometry at the draw-distance edge (escape hatch comparison:
  `-Dvkgpu.fullSceneDraw=true` should look identical inside the radius).
- Plane changes (castle stairs) and roof hiding inside buildings.
- Sub-worldview regression: board/observe sailing content; ship hulls render
  whole from all camera angles (`-Dvkgpu.disableSubWorldViews=true` to
  bisect if not).
- An instance (e.g. a quest instance).
- Alpha-heavy scenes (GE fountain, trees) for sort correctness.
- Disable and re-enable the plugin without restarting the client.
- Hop worlds.
- Resize the client window (storm of rapid resizes included).
- Collapse and expand the RuneLite sidebar on Linux/X11.
- Toggle MSAA off and back on; toggle FPS mode; toggle FSR on/off.
- Take a screenshot.
- On macOS, verify MoltenVK rendering and layer resize after moving/resizing
  the client window.
- Side-load the pluginJar into a STOCK RuneLite install (not `gradlew run`)
  on each platform.

## Release notes

- State supported platforms: Linux/X11 tested, macOS Apple Silicon tested,
  Windows x64 tested on NVIDIA. Intel Macs unsupported.
- State that validation layers are a developer-only option and should remain
  off for normal use.
- Tag the release; the hub descriptor pins the exact commit sha.
