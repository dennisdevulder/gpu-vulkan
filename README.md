# gpu-vulkan

Vulkan-backed renderer plugin for [RuneLite](https://runelite.net). A
work-in-progress alternative to the stock OpenGL GPU plugin.

Active development happens in the parent `runelite-vkport` tree; this
standalone repo is a snapshot intended for cross-platform development
(macOS, Windows, Wayland) where you don't want to pull the whole client
source tree.

## Status

- **Linux/X11** — working (daily driver). Several open issues — see
  `KNOWN_ISSUES.md`. Notable release blocker: sidebar-collapse crash
  (needs Vulkan-to-offscreen + GL blit refactor).
- **Windows** — surface code implemented, never tested.
- **macOS** — stub. Enabling the plugin throws "not implemented" by
  design. See `MacOSPlatformSurface.java` — wiring up requires
  `JAWTSurfaceLayers` + `CAMetalLayer` + `vkCreateMetalSurfaceEXT`,
  plus `VK_KHR_portability_enumeration` on the instance, plus the
  MoltenVK natives on the runtime classpath.

## Build

Requirements:

- JDK 11+
- `glslangValidator` on `PATH` (or set `GLSLANG=/path/to/glslangValidator`)
  - macOS: `brew install glslang`
  - Debian/Ubuntu: `apt install glslang-tools`
  - Fedora: `dnf install glslang`
- Vulkan loader on the host (MoltenVK on macOS — bundled with
  `lwjgl-vulkan` natives via the Vulkan SDK, or install separately)

```
./gradlew build
```

## Run from IDE

Open the project in IntelliJ. Run `GpuVulkanPluginTest#main` — it calls
`ExternalPluginManager.loadBuiltin()` and starts RuneLite with this
plugin already loaded.

The first launch will dial out for the published RuneLite client artifact
from `repo.runelite.net`. Then it boots like a normal RuneLite session
with **GPU (Vulkan)** in the plugin list.

## Side-loading into an installed RuneLite

`./gradlew jar` produces `build/libs/gpu-vulkan-*.jar`. Drop into
RuneLite's external plugins directory. Note this jar will NOT bring its
LWJGL Vulkan dependency along — for a self-contained build you'd need to
add a shadowJar task.

## Repo layout

```
src/main/java/...      plugin sources
src/main/shaders/...   GLSL — compiled to SPIR-V at build time
src/test/java/...      IDE-run main
build.gradle.kts       Gradle build (shader compile task, deps)
runelite-plugin.properties   plugin-hub-style descriptor
KNOWN_ISSUES.md        engineering notes; read before opening a PR
```

## License

BSD-2-Clause, matching upstream RuneLite.
