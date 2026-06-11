# gpu-vulkan

A Vulkan renderer for [RuneLite](https://runelite.net), as a replacement for
the stock OpenGL GPU plugin. It runs inside an unmodified client: no patches,
no fork, just a plugin that takes over `DrawCallbacks`. On macOS it renders
through MoltenVK, including Apple Silicon.

It also doubles as a Vulkan backend for other plugins. If you want to write
something GPU-accelerated (post-processing, compute, custom passes) you can
register a render extension and skip the painful parts: platform surfaces,
swapchain recreation, frame sync, UI compositing. The bundled FSR 1.0
upscaler is written entirely against this public API, so there's a real
worked example to crib from.

The renderer itself does what you'd expect from a GPU plugin and then some:
MSAA, anisotropic filtering, configurable draw distance with expanded map
loading, fog, skybox and roof handling, colour-blind modes, and FSR
upscaling (render the scene at reduced resolution, EASU + RCAS it back up,
composite the UI at full resolution). Normal overlays, infoboxes and the
CPU UI work as usual, and `DrawManager` screenshot consumers get a Vulkan
readback path. There's a plugin-by-plugin survey in
[docs/COMPATIBILITY_MATRIX.md](docs/COMPATIBILITY_MATRIX.md).

## Status

- **Linux/X11**: working (daily driver). Several open issues, see the
  issue tracker. The prior sidebar-collapse crash is mitigated by keeping
  an rlawt GLX context alive for AWT while Vulkan owns rendering.
- **macOS**: working on Apple Silicon via MoltenVK (bundled). Intel Macs
  are unsupported in v1 (no x64 MoltenVK in the plugin jar).
- **Windows**: working on x64, tested on NVIDIA.
- **Wayland**: via XWayland.

The plugin defers Vulkan startup until you are logged in. The login screen
renders on the CPU, so "nothing happens" at the login screen is expected.

Active development happens in the parent `runelite-vkport` tree; this
standalone repo is a snapshot intended for cross-platform development
(macOS, Windows, Wayland) where you don't want to pull the whole client
source tree.

## Requirements

- **JDK 21 (Eclipse Temurin)**, which is what the project is developed and
  tested against. Get it from
  [Adoptium](https://adoptium.net/temurin/releases/?version=21). The Gradle
  build still targets Java 11 bytecode so the plugin stays compatible with
  RuneLite's plugin runtime.

  Other JDKs may work, but watch out for Fedora's
  `java-25-openjdk-headless` package in particular: it ships without
  `libawt_xawt.so`, so RuneLite fails to open a window with a
  `HeadlessException` even when you have a working display. Install
  Temurin 21 alongside it and point `JAVA_HOME` / `update-alternatives`
  at the Temurin path.
- **`glslangValidator`** — optional, only needed when editing shaders. The
  compiled SPIR-V is committed under `src/main/resources/com/gpuvulkan/`;
  with the tool on `PATH` (or `GLSLANG=/path/to/glslangValidator`) the build
  regenerates it in place, without it the committed binaries are used and a
  freshness check fails the build if a shader source is newer.
  - macOS: `brew install glslang`
  - Debian/Ubuntu: `apt install glslang-tools`
  - Fedora: `dnf install glslang`
- **Vulkan loader** on the host. MoltenVK on macOS is bundled via
  `lwjgl-vulkan` natives, so no extra step there.

## Build

```
JAVA_HOME=/path/to/temurin-21 ./gradlew build
```

If `java -version` already points at a Temurin JDK, the `JAVA_HOME=` prefix
is unnecessary.

**macOS native helper**: `librlmtl.dylib` (the CAMetalLayer/JAWT bridge) is
committed under `src/main/resources/com/gpuvulkan/` like the shaders;
building on a Mac with `clang` regenerates it (universal arm64+x86_64,
ad-hoc signed). Gatekeeper may quarantine the dylib when the jar was
downloaded from a browser; `xattr -d com.apple.quarantine <jar>` clears it.

## Run

Three ways, in increasing order of "useful to other people":

### 1. From your IDE

Open the project in IntelliJ (or any Gradle-aware IDE) and run
`GpuVulkanPluginTest#main`. RuneLite boots with this plugin already on its
classpath; **GPU (Vulkan)** shows up in the plugin list.

### 2. `./gradlew run`

Same entry point as the IDE, from a terminal:

```
JAVA_HOME=/path/to/temurin-21 ./gradlew run
```

### 3. Standalone runnable jar

`./gradlew shadowJar` produces a self-contained jar at
`build/libs/gpu-vulkan-<version>-all.jar` (~41 MB) bundling a full RuneLite
client + this plugin + LWJGL natives for Linux, Windows, and macOS
(including MoltenVK for x64 + arm64). Run it directly:

```
/path/to/temurin-21/bin/java -ea -jar build/libs/gpu-vulkan-<version>-all.jar
```

This is the form to hand to a tester who isn't building from source. The
jar honours `~/.runelite/` so it won't disturb an existing RuneLite
install's character / plugin / config state.

The `-ea` flag enables assertions, matching what the plugin-hub template
does. Helps surface plugin bugs early.

### Side-loading into an installed RuneLite

`./gradlew jar` produces a slim `build/libs/gpu-vulkan-<version>.jar`
(plugin classes + shaders only, no bundled deps) that drops into RuneLite's
external plugins directory. This jar does NOT carry its LWJGL Vulkan
dependency, so RuneLite needs to already have `lwjgl-vulkan` on its
classpath, which the stock installer does not. For a self-contained binary
use `shadowJar` (#3 above).

## Writing a render extension

`GpuVulkanPlugin` owns RuneLite's `DrawCallbacks` slot and exposes a small
backend service. Registering an extension is two lines:

```java
@Inject
private VulkanRenderBackend vulkanBackend;

private AutoCloseable registration;

void start()
{
    registration = vulkanBackend.registerExtension(new MyRenderExtension());
}

void stop() throws Exception
{
    if (registration != null)
    {
        registration.close();
    }
}
```

Extensions implement `VulkanRenderExtension`. The backend fans out scene
capture, dynamic model capture, config changes, pre-renderpass upload
hooks, and in-renderpass command recording through that interface. The
plugin's own stock-parity scene/UI renderer (`BaseRenderer`) is registered
through the exact same path, so every frame this plugin draws goes through
the extension model.

`RenderDevice` (via `VulkanRenderContext.renderer()`) creates SPIR-V shader
modules, bind group layouts/groups, render pipelines (with optional vertex
input), GPU buffers (vertex/index/uniform/storage), compute pipelines,
streaming images, and offscreen `RenderTarget`s. `RenderEncoder` records
draws, indexed draws, compute dispatches, and extension-owned render passes
(`beginPass`/`endPass`/`prepareForSampling`).

Extensions that want the stock scene capture/draw path can request their
own `VulkanSceneRenderer` through `createSceneRenderer()` instead of
touching backend-owned Vulkan internals. Anything not covered drops to the
raw handles on `VulkanRenderContext` plus the raw `VkCommandBuffer` hooks;
see [docs/RENDERER_CONTRACT.md](docs/RENDERER_CONTRACT.md) for the
invariants you must keep.

For recorder-style plugins, the `recordAfterComposite` hook hands
extensions the final composited frame (scene + UI) on the graphics command
buffer right before present, with a documented layout contract for copying
it out. A worked consumer (GPU replay buffer with hotkey MP4 clips, plus
the Vulkan video encode queue plumbing it needs) lives on the
`feat/vulkan-video-recorder` branch.

### Worked example: the FSR upscaler

The bundled FSR 1.0 upscaler (`FsrUpscalerExtension`) is implemented
entirely against this public API and is the reference for full-scene
post-processing via `ScenePassRedirect`:

1. `scenePassRedirect()` returns non-null when upscaling is active; the
   backend then renders the 3D scene into the extension's low-resolution
   `RenderTarget` instead of the screen.
2. `recordAfterScene(cmd)` runs between passes: it transitions the scene
   target for sampling and records an EASU pass into a full-resolution
   intermediate target it owns.
3. `recordResolve(frame)` runs inside the final on-screen pass, drawing the
   RCAS-sharpened result just before the UI composite.

Resize handling, bind-group lifetime on target recreation, pipeline
compatibility (pipelines come from `RenderTarget.device()` for offscreen
passes), and pass bracketing are all visible in that one file. Start there
before writing your own extension.

## Escape hatches

System properties for diagnosing problems in the field; all default off.
Set them in RuneLite's JVM arguments (e.g. `-Dvkgpu.fullSceneDraw=true`).

| Property | Effect |
|---|---|
| `vkgpu.validation` | Force Vulkan validation layers on (same as the Debug config toggle) |
| `vkgpu.fullSceneDraw` | Disable draw-distance zone culling — try this first on missing-geometry reports |
| `vkgpu.disableFrustumCull` | Disable frustum zone culling only |
| `vkgpu.disableSubWorldViews` | Drop sub-worldview (ship) rendering, bisects sailing-content issues |
| `vkgpu.disableCustomPresent` | macOS: fall back from the Metal present path to vkQueuePresentKHR |
| `vkgpu.skipScreenshotReadback` | Disable the screenshot GPU readback |
| `vkgpu.stats` | Log the per-second `recon` diagnostics line |
| `vkgpu.modelStats` | Default-on for the detailed model stats config toggle |
| `vkgpu.resizeTrace` | Log canvas/surface resize tracing |

## Repo layout

```
src/main/java/...      plugin sources
src/main/shaders/...   GLSL sources (compiled .spv is committed in resources)
src/test/java/...      IDE-run main
build.gradle           Gradle build (shader compile task, deps)
runelite-plugin.properties   plugin-hub-style descriptor
docs/KNOWN_ISSUES.md         engineering notes; read before opening a PR
docs/RENDERER_CONTRACT.md    invariants for extensions using raw handles
docs/COMPATIBILITY_MATRIX.md plugin-by-plugin compatibility survey
docs/RELEASE_CHECKLIST.md    production-readiness gates
```

## Contributing

PRs welcome. Two ground rules:

1. **Match upstream RuneLite's API surface.** This plugin runs inside an
   unmodified RuneLite client; anything that requires patching the host
   won't merge. If you find something the public API doesn't expose, open
   an issue before working around it.
2. **Be upfront about LLM use.** Using an assistant is fine and encouraged
   for boilerplate, refactors, and porting, but reviewers need to know
   where to look more carefully. Follow the Linux kernel convention and add
   an `Assisted-by:` trailer to commits where the assistant materially
   shaped the code (new files, multi-file refactors, design decisions).
   Example:

   ```
   feat(scene): implement per-zone vertex cache

   <body>

   Signed-off-by: Your Name
   Assisted-by: Claude Opus 4.7
   ```

   Trivial autocomplete doesn't need the trailer. Don't use
   `Co-Authored-By:`, which implies joint authorship; an LLM can't be a
   joint author.

## License

BSD-2-Clause, matching upstream RuneLite.
