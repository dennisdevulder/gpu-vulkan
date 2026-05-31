# GPU (Vulkan) Release Checklist

This checklist is for production readiness. It deliberately excludes renderer
optimization work.

## Build gates

- `JAVA_HOME=/path/to/temurin-21 ./gradlew test`
- `JAVA_HOME=/path/to/temurin-21 ./gradlew build`
- `JAVA_HOME=/path/to/temurin-21 ./gradlew pluginJar`
- `JAVA_HOME=/path/to/temurin-21 ./gradlew shadowJar`
- Confirm `runelite-plugin.properties` has a real `support=` URL.
- Confirm release artifacts are not tracked under `build/`.
- Confirm no `hs_err_pid*.log`, `replay_pid*`, or ad-hoc debug artifacts are in
  the working tree.

## Manual smoke test

- Enable plugin from a clean RuneLite profile.
- Log in and render a busy scene for at least five minutes.
- Disable and re-enable the plugin without restarting the client.
- Hop worlds.
- Resize the client window.
- Collapse and expand the RuneLite sidebar on Linux/X11.
- Toggle MSAA off and back on.
- Toggle unlocked FPS mode.
- Take a screenshot.
- On macOS, verify MoltenVK rendering and layer resize after moving/resizing the
  client window.

## Release notes

- State supported platforms: Linux/X11 tested, macOS Apple Silicon tested,
  Windows surface code present but needs external validation.
- State that validation layers are a developer-only option and should remain off
  for normal use.
- RuneLite dependency version for this release: `1.12.27`.
