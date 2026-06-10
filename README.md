# gpu-vulkan

Vulkan-backed renderer plugin for [RuneLite](https://runelite.net). A
work-in-progress alternative to the stock OpenGL GPU plugin.

This repository is also the first pass at a reusable Vulkan backend for
RuneLite plugins. The goal is that plugin authors do not each have to solve
platform surfaces, Vulkan instance/device creation, swapchain recreation,
frame sync, UI compositing, and DrawCallbacks ownership from scratch.

Active development happens in the parent `runelite-vkport` tree; this
standalone repo is a snapshot intended for cross-platform development
(macOS, Windows, Wayland) where you don't want to pull the whole client
source tree.

## Status

- **Linux/X11** — working (daily driver). Several open issues — see
  the issue tracker. The prior sidebar-collapse crash is mitigated by
  keeping an rlawt GLX context alive for AWT while Vulkan owns rendering.
- **macOS** — working on Apple Silicon via MoltenVK.
- **Windows** — working, tested on NVIDIA.

The plugin defers Vulkan startup until you are logged in — the login
screen renders on the CPU, so "nothing happens" at the login screen is
expected.

## Requirements

- **JDK 21 (Eclipse Temurin)** — this is what the project is developed
  and tested against. Get it from
  [Adoptium](https://adoptium.net/temurin/releases/?version=21).
  The Gradle build still targets Java 11 bytecode so the plugin stays
  compatible with RuneLite's plugin runtime.

  Other JDKs may work but watch out for **Fedora's
  `java-25-openjdk-headless` package** in particular — it ships without
  `libawt_xawt.so`, so RuneLite fails to open a window with a
  `HeadlessException` even when you have a working display. Install
  Temurin 21 alongside it and point `JAVA_HOME` /
  `update-alternatives` at the Temurin path.
- **`glslangValidator`** on `PATH` (or set
  `GLSLANG=/path/to/glslangValidator`):
  - macOS: `brew install glslang`
  - Debian/Ubuntu: `apt install glslang-tools`
  - Fedora: `dnf install glslang`
- **Vulkan loader** on the host — MoltenVK on macOS is bundled inside
  the shadowJar via `lwjgl-vulkan` natives, so no extra step there.

## Build

```
JAVA_HOME=/path/to/temurin-21 ./gradlew build
```

If `java -version` already points at a Temurin JDK, the `JAVA_HOME=`
prefix is unnecessary.

**macOS native helper**: `librlmtl.dylib` (the CAMetalLayer/JAWT
bridge) is compiled as a universal arm64+x86_64 binary and ad-hoc
signed, but only when building **on a Mac** — jars built on Linux or
Windows do not contain it and won't render on macOS. Release jars
intended for Mac users must be cut on macOS. Gatekeeper may still
quarantine the dylib when the jar was downloaded from a browser;
`xattr -d com.apple.quarantine <jar>` clears it.

## Run

Three ways, in increasing order of "useful to other people":

### 1. From your IDE

Open the project in IntelliJ (or any Gradle-aware IDE) and run
`GpuVulkanPluginTest#main`. RuneLite boots with this plugin already on
its classpath; **GPU (Vulkan)** shows up in the plugin list.

### 2. `./gradlew run`

Same entry point as the IDE, from a terminal:

```
JAVA_HOME=/path/to/temurin-21 ./gradlew run
```

### 3. Standalone runnable jar

`./gradlew shadowJar` produces a self-contained jar at
`build/libs/gpu-vulkan-<version>-all.jar` (~41 MB) bundling a full
RuneLite client + this plugin + LWJGL natives for Linux, Windows, and
macOS (including MoltenVK for x64 + arm64). Run it directly:

```
/path/to/temurin-21/bin/java -ea -jar build/libs/gpu-vulkan-<version>-all.jar
```

This is the form to hand to a tester who isn't building from source.
The jar honours `~/.runelite/` so it won't disturb an existing
RuneLite install's character / plugin / config state.

The `-ea` flag enables assertions, matching what the plugin-hub
template does — helps surface plugin bugs early.

## Side-loading into an installed RuneLite

`./gradlew jar` produces a slim `build/libs/gpu-vulkan-<version>.jar`
(plugin classes + shaders only, no bundled deps) that drops into
RuneLite's external plugins directory. This jar does NOT carry its
LWJGL Vulkan dependency, so RuneLite needs to already have
`lwjgl-vulkan` on its classpath — which the stock installer does not.
For a self-contained binary use `shadowJar` (#3 above).

## Vulkan backend API

`GpuVulkanPlugin` owns RuneLite's `DrawCallbacks` slot and exposes a small
backend service:

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
capture, dynamic model capture, config changes, pre-renderpass upload hooks,
and in-renderpass command recording through that interface. The current
stock-parity scene/UI renderer is registered through the same path as
`BaseRenderer`, so this plugin exercises the extension model
instead of bypassing it.

`VulkanRenderContext` exposes the shared rendering-device facade through
`renderer()` for shader modules, bind groups, pipelines and streaming images.
Extensions that want the stock scene capture/draw path can request their own
`VulkanSceneRenderer` through `createSceneRenderer()` instead of touching
backend-owned Vulkan internals directly.

`VulkanRenderContext.encode()` exposes Vulkan video encode capability data for
plugins such as trackers/recorders. It reports whether the selected physical
device has a video encode queue and H.264/H.265/AV1 encode extensions. The
current backend does not create an encode queue yet, so consumers must check
`VulkanEncodeContext.isAvailable()` before using queue handles.

The API is intentionally narrow right now. It is meant to establish the
backend boundary for future RuneLite Vulkan work, not to guarantee that HD
plugins can already swap in advanced materials, lighting, shadows, or
post-processing without additional API design.

## Repo layout

```
src/main/java/...      plugin sources
src/main/shaders/...   GLSL — compiled to SPIR-V at build time
src/test/java/...      IDE-run main
build.gradle           Gradle build (shader compile task, deps)
runelite-plugin.properties   plugin-hub-style descriptor
docs/KNOWN_ISSUES.md  engineering notes; read before opening a PR
docs/RELEASE_CHECKLIST.md  production-readiness gates
```

## Contributing

PRs welcome. Two ground rules:

1. **Match upstream RuneLite's API surface.** This plugin runs inside an
   unmodified RuneLite client; anything that requires patching the host
   won't merge. If you find something the public API doesn't expose,
   open an issue before working around it.
2. **Be upfront about LLM use.** Using an assistant is fine and
   encouraged for boilerplate, refactors, and porting — but reviewers
   need to know where to look more carefully. Follow the Linux kernel
   convention and add an `Assisted-by:` trailer to commits where the
   assistant materially shaped the code (new files, multi-file
   refactors, design decisions). Example:

   ```
   feat(scene): implement per-zone vertex cache

   <body>

   Signed-off-by: Your Name
   Assisted-by: Claude Opus 4.7
   ```

   Trivial autocomplete doesn't need the trailer. Don't use
   `Co-Authored-By:` — that implies joint authorship, which an LLM
   can't have.

## License

BSD-2-Clause, matching upstream RuneLite.
