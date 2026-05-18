# gpu-vulkan

Vulkan-backed renderer plugin for [RuneLite](https://runelite.net). A
work-in-progress alternative to the stock OpenGL GPU plugin.

Active development happens in the parent `runelite-vkport` tree; this
standalone repo is a snapshot intended for cross-platform development
(macOS, Windows, Wayland) where you don't want to pull the whole client
source tree.

## Status

- **Linux/X11** — working (daily driver). Several open issues — see
  the issue tracker. Notable release blocker: sidebar-collapse crash
  (needs Vulkan-to-offscreen + GL blit refactor).
- **macOS** — working on Apple Silicon via MoltenVK; one outstanding
  layer-flicker bug (see issues).
- **Windows** — surface code implemented, never tested. See
  [issue #4](https://github.com/dennisdevulder/gpu-vulkan/issues/4) if
  you have a Windows box to validate it.

## Requirements

- **JDK 21 (Eclipse Temurin)** — this is what the project is developed
  and tested against. Get it from
  [Adoptium](https://adoptium.net/temurin/releases/?version=21).

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

## Repo layout

```
src/main/java/...      plugin sources
src/main/shaders/...   GLSL — compiled to SPIR-V at build time
src/test/java/...      IDE-run main
build.gradle.kts       Gradle build (shader compile task, deps)
runelite-plugin.properties   plugin-hub-style descriptor
KNOWN_ISSUES.md        engineering notes; read before opening a PR
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
