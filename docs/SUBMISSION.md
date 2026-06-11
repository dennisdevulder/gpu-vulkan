# Plugin Hub submission

How to submit gpu-vulkan to the RuneLite Plugin Hub, and what the hub's
review will care about. Mechanics verified against the plugin-hub repo
(packager `Plugin.java`, `target_init.gradle`, `verification-template`).

## How the hub builds this plugin

The hub checks out the commit named in the descriptor and runs exactly two
injected Gradle tasks (`runelitePluginHubPackage`, `runelitePluginHubManifest`)
on an Ubuntu builder via the Gradle Tooling API. It has no glslangValidator
and no clang, which is why the compiled `.spv` and `librlmtl.dylib` are
committed under `src/main/resources/com/gpuvulkan/` and the custom compile
tasks skip when the toolchain is absent.

Hard rules the packager enforces:

- No classes under the `net/runelite/` namespace (we live in `com.gpuvulkan`).
- Jar size limit, default 10 MiB — our descriptor sets `jarSizeLimitMiB=15`
  (the jar is ~9.1 MiB; lwjgl-vulkan classes plus MoltenVK arm64 dominate).
- Dependencies not provided by runelite-client must be on the hub's
  verification whitelist (hash-verified).

## The PR

Fork `runelite/plugin-hub`, branch, and add `plugins/gpu-vulkan`:

```
repository=https://github.com/dennisdevulder/gpu-vulkan.git
commit=<full 40-char sha of the tagged release>
jarSizeLimitMiB=15
```

This submission ALSO needs `package/verification-template/build.gradle`
additions — `org.lwjgl:lwjgl-jawt:3.3.6` and
`org.lwjgl:lwjgl-vulkan:3.3.6:natives-macos-arm64` are not whitelisted yet
(`lwjgl-vulkan:3.3.6` itself is, via osrs-tracker). Add them under
`thirdParty` with a `because "gpu-vulkan"`, then regenerate the hashes:

```
cd package/verification-template
../gradlew --write-verification-metadata sha256
```

Dependency additions get manual maintainer review and are the slow path —
consider opening that part early. Fallback if it stalls: drop the mac
natives from v1 (Linux/Windows only, ~7.3 MiB) and ship macOS in v1.1;
lwjgl-jawt is required either way.

## Pre-submission checks

Run from a clean checkout of the exact release commit:

- [ ] `./gradlew pluginJar` succeeds with glslangValidator NOT installed
      (simulates the hub builder; committed `.spv` must package as-is)
- [ ] `unzip -l build/libs/*-plugin.jar | grep -c 'net/runelite.*class'` is 0
- [ ] Jar under the size limit; no unexpected natives or deps inside
- [ ] All classes ≤ Java 11 bytecode (build targets 11; spot-check with
      `javap -v` if the toolchain changed)
- [ ] `librlmtl.dylib` present in the jar and freshly built from the release
      commit's `rlmtl.m` (JNI symbols encode the package name — a stale
      dylib from before the `com.gpuvulkan` move will not bind)

## Reviewer flashpoints, pre-answered

- **Reflection on `DrawManager.nextFrame`** — the plugin's only reflective
  access, isolated in `DrawManagerScreenshotProbe` with the full rationale
  in its javadoc: there is no public way to peek whether a screenshot
  consumer is waiting without consuming the queue. Fails safe (readback
  disables itself). An upstream `DrawManager.hasNextFrameRequest()` would
  delete the class.
- **Committed binaries** (`.spv`, `.dylib`) — regenerable from source in
  this repo with standard tooling (glslang, clang); build tasks regenerate
  in place when the tools are present, and a freshness check fails the
  build when a shader source is newer than its committed SPIR-V.
- **Jar size** — lwjgl-vulkan's class surface is ~7 MiB on its own; the
  rest is MoltenVK for Apple Silicon and six SPIR-V files.
- **`warning=` in the descriptor** — intentional: experimental renderer,
  surfaced to the user at install time.
